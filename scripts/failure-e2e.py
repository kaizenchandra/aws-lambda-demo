#!/usr/bin/env python3
"""Isolated local fault queue invokes the real deployed worker and verifies redrive."""
import json
import subprocess
import uuid
from e2e import ROOT, aws, output, eventually, request

if not output('api_url').startswith('http://localhost:4566/'):
    raise SystemExit('LocalStack only')
suffix = str(uuid.uuid4())
role = 'commerce-local-fulfillment-worker'
policy_name = 'fault-test-' + suffix
queue = dead = mapping = None
policy_added = False
try:
    dead = aws('sqs', 'create-queue', '--queue-name', 'commerce-local-fault-dlq-' + suffix)['QueueUrl']
    dead_arn = aws('sqs', 'get-queue-attributes', '--queue-url', dead,
                   '--attribute-names', 'QueueArn')['Attributes']['QueueArn']
    queue = aws('sqs', 'create-queue', '--queue-name', 'commerce-local-fault-' + suffix,
                '--attributes', json.dumps({'VisibilityTimeout': '30', 'ReceiveMessageWaitTimeSeconds': '1',
                'RedrivePolicy': json.dumps({'deadLetterTargetArn': dead_arn, 'maxReceiveCount': 2})}))['QueueUrl']
    queue_arn = aws('sqs', 'get-queue-attributes', '--queue-url', queue,
                    '--attribute-names', 'QueueArn')['Attributes']['QueueArn']
    policy = {'Version': '2012-10-17', 'Statement': [{'Effect': 'Allow',
              'Action': ['sqs:ReceiveMessage', 'sqs:DeleteMessage', 'sqs:GetQueueAttributes'], 'Resource': queue_arn}]}
    aws('iam', 'put-role-policy', '--role-name', role, '--policy-name', policy_name,
        '--policy-document', json.dumps(policy))
    policy_added = True
    mapping = aws('lambda', 'create-event-source-mapping', '--function-name', role + ':live',
                  '--event-source-arn', queue_arn, '--batch-size', '2',
                  '--function-response-types', 'ReportBatchItemFailures')['UUID']
    eventually(lambda: aws('lambda', 'get-event-source-mapping', '--uuid', mapping)['State'] == 'Enabled', 60)
    poison = 'invalid-event-' + suffix
    aws('sqs', 'send-message', '--queue-url', queue, '--message-body', poison)
    status, good = request('/orders', {'items': [{'sku': 'AWS-GUIDE', 'quantity': 1}]}, 'failure-' + suffix)
    assert status == 202, (status, good)

    def dead_letter():
        messages = aws('sqs', 'receive-message', '--queue-url', dead,
                       '--wait-time-seconds', '1', '--max-number-of-messages', '10',
                       '--message-system-attribute-names', 'ApproximateReceiveCount').get('Messages', [])
        for message in messages:
            if message['Body'] == poison:
                assert int(message['Attributes']['ApproximateReceiveCount']) >= 2
                return message['Attributes']
        return None

    evidence = eventually(dead_letter, 150)
    eventually(lambda: request('/fulfillments/' + good['orderId'])[1].get('status') == 'COMPLETED', 180)
    result = {'poisonMessageRedriven': True, 'receiveAttributes': evidence,
              'healthyOrderCompleted': good['orderId'], 'workerAlias': role + ':live',
              'testQueueVisibilitySeconds': 30, 'testMaxReceiveCount': 2}
    (ROOT / '.local/failure-e2e-result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
finally:
    if mapping:
        aws('lambda', 'delete-event-source-mapping', '--uuid', mapping)
        def mapping_deleted():
            try:
                aws('lambda', 'get-event-source-mapping', '--uuid', mapping)
                return False
            except subprocess.CalledProcessError as error:
                if 'ResourceNotFoundException' in error.stderr:
                    return True
                raise
        eventually(mapping_deleted, 60)
    if policy_added:
        aws('iam', 'delete-role-policy', '--role-name', role, '--policy-name', policy_name)
    for url in (queue, dead):
        if url:
            aws('sqs', 'delete-queue', '--queue-url', url)
