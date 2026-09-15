#!/usr/bin/env python3
"""Deploy first with init-localstack.sh; this script is intentionally local-only."""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
OUTPUTS = json.loads((ROOT / '.local/outputs.json').read_text())

def output(name):
    return OUTPUTS[name]['value']


def aws(*args):
    env = os.environ | {'AWS_ACCESS_KEY_ID': 'test', 'AWS_SECRET_ACCESS_KEY': 'test',
                        'AWS_DEFAULT_REGION': 'us-east-1', 'AWS_PAGER': ''}
    env.pop('AWS_SESSION_TOKEN', None)
    result = subprocess.run(['aws', '--endpoint-url', 'http://localhost:4566', *args],
                            env=env, text=True, capture_output=True, check=True)
    return json.loads(result.stdout) if result.stdout.strip() else {}


def request(path, body=None, key=None):
    url = output('api_url') + path
    if not url.startswith('http://localhost:4566/'):
        raise RuntimeError('E2E mutations are allowed only against localhost:4566')
    headers = {'Content-Type': 'application/json'}
    if key:
        headers['Idempotency-Key'] = key
    req = urllib.request.Request(url, data=json.dumps(body).encode() if body is not None else None,
                                 headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=35) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def eventually(check, seconds=180):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = check()
        if value:
            return value
        time.sleep(1)
    raise AssertionError(f'Expected system state not reached within {seconds}s')


def item(table, key):
    return aws('dynamodb', 'get-item', '--table-name', table, '--consistent-read',
               '--key', json.dumps({'pk': {'S': key}})).get('Item', {})


def event_id(order):
    return str(uuid.UUID(bytes=hashlib.md5((order + ':accepted:1').encode()).digest(), version=3))


def main():
    key = 'e2e-' + str(uuid.uuid4())
    body = {'items': [{'sku': 'JAVA-GUIDE', 'quantity': 2}, {'sku': 'AWS-GUIDE', 'quantity': 1}]}
    assert request('/orders', {'items': []}, key)[0] == 400
    status, order = request('/orders', body, key)
    assert status == 202, (status, order)
    order_id = order['orderId']
    assert request('/orders', body, key)[1]['orderId'] == order_id
    assert request('/orders', {'items': [{'sku': 'JAVA-GUIDE', 'quantity': 1}]}, key)[0] == 409
    assert request('/orders/' + order_id)[0] == 200
    stored_order = json.loads(item(output('ordering_table'), 'ORDER#' + order_id)['body']['S'])
    assert stored_order['customerId'] == 'local-customer'
    assert len(stored_order['lines']) == 2

    def completed():
        status, value = request('/fulfillments/' + order_id)
        return value if status == 200 and value['status'] == 'COMPLETED' else None

    fulfillment = eventually(completed)
    state = json.loads(item(output('fulfillment_table'), 'FULFILLMENT#' + order_id)['body']['S'])
    assert state['version'] == 1 and state['status'] == 'COMPLETED'
    receipt_path = ROOT / '.local' / f'{order_id}.txt'
    aws('s3api', 'get-object', '--bucket', output('receipts_bucket'), '--key', fulfillment['receiptKey'], str(receipt_path))
    receipt = receipt_path.read_text()
    assert order_id in receipt and '99.70' in receipt

    def completion_event():
        messages = aws('sqs', 'receive-message', '--queue-url', output('completion_queue_url'),
                       '--max-number-of-messages', '10', '--wait-time-seconds', '1',
                       '--visibility-timeout', '2').get('Messages', [])
        for message in messages:
            event = json.loads(message['Body'])
            if event['aggregateId'] == order_id:
                assert event['eventType'] == 'FulfillmentCompleted'
                assert event['causationId'] == event_id(order_id)
                aws('sqs', 'delete-message', '--queue-url', output('completion_queue_url'),
                    '--receipt-handle', message['ReceiptHandle'])
                return event
        return None

    completion = eventually(completion_event)
    accepted = item(output('ordering_table'), 'OUTBOX#' + event_id(order_id))['body']['S']
    # Exercise a deployed duplicate invocation synchronously so the assertion cannot race consumption.
    duplicate_batch = {'Records': [{'messageId': str(uuid.uuid4()), 'body': accepted}]}
    response_file = ROOT / '.local' / 'duplicate-response.json'
    metadata = aws('lambda', 'invoke', '--function-name', 'commerce-local-fulfillment-worker:live',
                   '--cli-binary-format', 'raw-in-base64-out', '--payload', json.dumps(duplicate_batch), str(response_file))
    assert 'FunctionError' not in metadata, metadata
    assert json.loads(response_file.read_text())['batchItemFailures'] == []
    # A real deployed mixed batch must acknowledge the completed record and return only poison.
    poison_id = 'partial-' + str(uuid.uuid4())
    duplicate_batch['Records'].append({'messageId': poison_id, 'body': 'invalid-json'})
    metadata = aws('lambda', 'invoke', '--function-name', 'commerce-local-fulfillment-worker:live',
                   '--cli-binary-format', 'raw-in-base64-out', '--payload', json.dumps(duplicate_batch), str(response_file))
    assert 'FunctionError' not in metadata, metadata
    assert json.loads(response_file.read_text())['batchItemFailures'] == [{'itemIdentifier': poison_id}]
    assert json.loads(item(output('fulfillment_table'), 'FULFILLMENT#' + order_id)['body']['S']) == state
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        replays = list(executor.map(lambda _: request('/orders', body, key), range(4)))
    assert all(code == 202 and value['orderId'] == order_id for code, value in replays)
    evidence = {'orderId': order_id, 'fulfillment': state['status'], 'receiptKey': fulfillment['receiptKey'],
                'completionEventId': completion['eventId'], 'duplicateInvocation': 'PASS', 'concurrentApiReplay': 'PASS', 'partialBatchInvocation': 'PASS'}
    (ROOT / '.local/e2e-result.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print(json.dumps(evidence, indent=2))

if __name__ == '__main__':
    main()
