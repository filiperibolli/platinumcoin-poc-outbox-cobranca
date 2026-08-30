#!/bin/bash
# Roda automaticamente no LocalStack (/etc/localstack/init/ready.d).
# Cria a fila que o mainframe legado consome.
set -euo pipefail

awslocal sqs create-queue --queue-name lancamentos-contabeis

echo "[init] fila lancamentos-contabeis criada"
awslocal sqs list-queues
