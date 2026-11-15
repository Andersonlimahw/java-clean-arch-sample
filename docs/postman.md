---
title: Postman — fluxo completo da API
status: active
updated: 2026-11-14
related: [api.md, testing.md]
---

# Postman — fluxo completo da API

Importe [payments-v1.postman_collection.json](../src/test/postman/payments-v1.postman_collection.json). A collection é estruturada inteiramente em inglês, contém documentação técnica completa na aba **Docs** (`info.description`) e valida todos os endpoints de negócio dos módulos de autenticação, pagamentos e notificações, além de readiness, idempotência e exemplos de erro RFC 7807. Não requer environment.

## Preparação

Inicie a aplicação conforme o [README](../README.md). PostgreSQL, Redis, RabbitMQ e o WireMock de fraude devem estar disponíveis:

```bash
make up
# ou com depurador JDWP anexado:
make debug
```

O WireMock vem pré-configurado com mappings em `deploy/wiremock/mappings/`:
- `fraud-clear.json` (prioridade 10): triagem automática `CLEAR` para valores comuns (< 5.000,00).
- `fraud-under-review.json` (prioridade 1): triagem manual `UNDER_REVIEW` para transações de alto valor (>= 5.000,00).

Configure `baseUrl` na aba Variables da collection (padrão `http://127.0.0.1:8080`). Use somente ambiente local/de teste: a execução cadastra usuário e cria pagamentos e notificações persistidos.

Execute a collection inteira no Runner, em ordem, com **Keep variable values** habilitado para preservar as alterações depois da execução. Não crie variáveis de environment/data com os mesmos nomes: esses escopos podem sobrescrever os valores da collection na resolução das requests.

## Ordem de execução

| Pasta | Fluxo e resultados |
|---|---|
| 01 · Availability | Readiness deve responder `200` e `UP`. |
| 02 · Identity & Authentication | Cadastro `201`, login `200`, rotação `200`; salva usuário e tokens JWT RS256. |
| 03 · Payments & Transactions | Cria (`201 PENDING`), repete chave sem duplicar (`201 Idempotent-Replayed: true`), consulta, pagina, confirma (`200 COMPLETED`); cria segundo pagamento e cancela (`200 CANCELLED`); cria dois pagamentos de alto valor ($5.000,00 e $5.001,00) que resultam em `UNDER_REVIEW` e preenchem `heldPaymentIdApprove` e `heldPaymentIdReject`. |
| 04 · Notifications & Event Delivery | Valida contrato e proprietário; aguarda notificação assíncrona do pagamento via RabbitMQ com até dez repetições e intervalo de 1s. |
| 05 · Negative Scenarios & Error Contracts | Verifica `400`, `401`, `403`, `404` e `409`, incluindo payload RFC 7807 (`application/problem+json`) e transições inválidas. Todos produzem testes verdes. |
| 06 · Fraud Review & Compliance (Optional) | Opcional; desabilitada por padrão (`runFraudReview=false`). Quando habilitada, lista os pagamentos em revisão (alimentados automaticamente pelas etapas 09 e 10 da pasta 03), aprova o primeiro (`PENDING`) e rejeita o segundo (`CANCELLED`). Exige token de revisor (`ADMIN` ou `FRAUD_REVIEWER`). |
| 07 · Session Teardown & Logout | Logout `204` e tentativa de refresh com token revogado `401`. Execute por último. |

Os scripts verificam status, Content-Type, schemas de payload (JSON Schema Draft-07), propriedade dos recursos, estados de pagamento, replay de idempotência e rotação de tokens. A etapa 03.06b consulta o histórico paginado, aguarda até dez ciclos de 1s e exige os eventos de criação e confirmação em ordem, com ator e estado corretos. As respostas `204` devem ter corpo vazio. Valores só são capturados em respostas de sucesso com os campos esperados.

## Variáveis preservadas

| Variável | Uso |
|---|---|
| `baseUrl` | URL base do gateway da API (padrão `http://127.0.0.1:8080`). |
| `email`, `password`, `displayName` | Credenciais e nome usados no cadastro e login. Email único gerado por padrão; senha de exemplo aleatória gerada somente quando vazia. |
| `newUserOnRun` | `true` gera novo email no cadastro. Para uma conta existente, execute Login diretamente, sem executar Cadastro novamente. |
| `userId` | UUID retornado em `id` pelo cadastro. |
| `accessToken`, `refreshToken`, `tokenExpiresAt` | Login e refresh atualizam os tokens; Bearer herdado da collection nas requests protegidas. Expiração em milissegundos Unix. |
| `amount`, `currency`, `paymentDescription` | Dados de exemplo do pagamento padrão (`15.50`, `BRL`). |
| `idempotencyKey`, `paymentId` | Chave da criação principal e ID usado em replay, consulta e confirmação. |
| `cancelPaymentId` | Segundo pagamento, exclusivo do fluxo de cancelamento. |
| `heldPaymentIdApprove` | Pagamento de alto valor ($5.000,00) retido em `UNDER_REVIEW` no passo 03.09, aprovado no passo 06.02. |
| `heldPaymentIdReject` | Pagamento de alto valor ($5.001,00) retido em `UNDER_REVIEW` no passo 03.10, rejeitado no passo 06.03. |
| `notificationId`, `notificationPoll` | ID capturado da notificação e contador de polling limitado. |
| `runFraudReview` | `false` por padrão. Pula a pasta 06 no Runner/Newman se falso. |
| `reviewerAccessToken` | Token de outro usuário com `ADMIN` ou `FRAUD_REVIEWER`. |

As requests públicas usam No Auth; as protegidas herdam Bearer da collection. A pasta 06 possui Bearer próprio de revisor. Não existe endpoint público de promoção de roles: para testar a pasta 06, promova um usuário de teste no PostgreSQL (`UPDATE users SET roles = 'USER,FRAUD_REVIEWER' WHERE email = '...';`), faça login via `/api/v1/auth/login`, preencha `reviewerAccessToken` e defina `runFraudReview=true`.

Em Send isolado, o polling de notificações não encadeia requests; use Runner/Newman para aguardar entrega eventual. A aplicação mantém JWTs de acesso válidos até a expiração após logout; a família de refresh é revogada. As variáveis ficam disponíveis para inspeção, mas o refresh salvo deixa de ser utilizável. Execute Login novamente para iniciar outra sessão.

## Newman

Com Node.js instalado, execute a collection via `npx newman`:

```bash
npx newman run src/test/postman/payments-v1.postman_collection.json --delay-request 250 --timeout-request 10000 --timeout-script 5000
```

Para executar incluindo a pasta 06 com token de revisor:

```bash
npx newman run src/test/postman/payments-v1.postman_collection.json \
  --global-var "runFraudReview=true" \
  --global-var "reviewerAccessToken=<TOKEN_DO_REVISOR>" \
  --delay-request 250 \
  --timeout-request 10000 \
  --timeout-script 5000
```

Newman atualiza e reutiliza variáveis durante o run, mas não grava automaticamente a collection de origem. Na versão 6.2.3, validada localmente, `--export-collection` exportou os valores iniciais em vez das alterações de `pm.collectionVariables`. Portanto, não use esse parâmetro como garantia de persistência entre processos. Para manter os valores após executar, use o Runner do Postman com **Keep variable values**. Um runner programático que precise exportar estado deve capturar explicitamente o escopo atualizado dos eventos de script e verificar o arquivo produzido. Qualquer export com credenciais deve permanecer privado, fora do checkout; não anexe, publique ou versione senha e tokens reais.

## Regras de manutenção

- Alterou endpoint, DTO, status ou autenticação: atualize a collection e seus testes na mesma mudança.
- Use `pm.collectionVariables` para estado entre requests; não introduza dependência de globals ou environment.
- Preserve o encadeamento; confirmação e cancelamento precisam de pagamentos distintos.
- Valide status exato e payload, inclusive nos erros; não aceite intervalos de status para esconder falhas.
- Não use tokens reais como exemplos nem transforme cenários opcionais pulados em resultado aprovado.

## Eval

Evidência da execução real: [validação local de 2026-11-13](test-plans/postman-validation.md).

- Importe a collection sem environment e execute o fluxo padrão: nenhum placeholder sem valor; cadastro, login e tokens encadeados.
- Repita o fluxo: novo email, nova chave e novos IDs; credenciais e IDs disponíveis na collection ao final com Keep variable values.
- Desconfigure o screening CLEAR: a criação não deve ser reportada como PENDING bem-sucedido.
- Execute com infraestrutura completa: notificação deve referenciar `paymentId` e `userId`.
- Execute a pasta opcional com `runFraudReview=true` e `reviewerAccessToken`: confirme que as requisições 03.09 e 03.10 alimentam a fila `UNDER_REVIEW`, e que a aprovação transiciona para `PENDING` e a rejeição para `CANCELLED`.
- `rtk proxy bash scripts/validate-docs.sh` e `rtk git diff --check`.
