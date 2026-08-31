#!/bin/bash
# Roda automaticamente no LocalStack (/etc/localstack/init/ready.d).
# Cria a fila que o mainframe legado consome e o bucket dos artefatos do ciclo.
set -euo pipefail

awslocal sqs create-queue --queue-name lancamentos-contabeis
echo "[init] fila lancamentos-contabeis criada"

# Remessas (step-07) e retornos arquivados (step-09) moram aqui. Sem expurgo:
# em produção seria uma lifecycle policy, com prazo ditado pela guarda contábil.
awslocal s3 mb s3://cobranca-artefatos
# ÚLTIMA linha do script de propósito: é nela que os testes esperam para começar.
echo "[init] bucket cobranca-artefatos criado"
