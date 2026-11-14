---
title: Postman — fluxo completo da API
status: active
updated: 2026-11-13
related: [api.md, testing.md]
---

# Postman — fluxo completo da API

Importe [payments-v1.postman_collection.json](../src/test/postman/payments-v1.postman_collection.json). A collection contém todos os endpoints de negócio dos controllers de autenticação, pagamentos e notificações, além de readiness e exemplos de erros. Não precisa de environment.

## Preparação

Inicie a aplicação conforme o [README](../README.md). PostgreSQL, Redis, RabbitMQ e o WireMock de fraude devem estar disponíveis. O fluxo principal espera screening `CLEAR`, como na configuração Compose do repositório. Configure `baseUrl` na aba Variables da collection (padrão `http://127.0.0.1:8080`). Use somente ambiente local/de teste: a execução cadastra usuário e cria pagamentos e notificações persistidos.

Execute a collection inteira no Runner, em ordem, com **Keep variable values** habilitado para preservar as alterações depois da execução. Não crie variáveis de environment/data com os mesmos nomes: esses escopos podem sobrescrever os valores da collection na resolução das requests.

## Ordem de execução

| Pasta | Fluxo e resultados |
|---|---|
| 01 · Disponibilidade | Readiness deve responder `200` e `UP`. |
| 02 · Conta e autenticação | Cadastro `201`, login `200`, rotação `200`; salva usuário e tokens. |
| 03 · Pagamentos | Cria, repete chave sem duplicar, consulta, pagina, confirma; cria outro pagamento e cancela. |
| 04 · Notificações | Valida contrato e proprietário; aguarda notificação do pagamento com até dez repetições e intervalo de 1s. |
| 05 · Erros esperados | Verifica `400`, `401`, `403`, `404` e `409`, incluindo payload RFC 7807 e transições inválidas. Esses erros esperados devem produzir testes verdes. |
| 06 · Revisão de fraude | Opcional; desabilitada por padrão. Exemplos de listagem, aprovação e rejeição. |
| 07 · Encerrar sessão | Logout `204` e tentativa de refresh revogado `401`. Execute por último. |

Os scripts verificam status, Content-Type, schemas de payload, propriedade dos recursos, estados de pagamento, replay de idempotência e rotação de tokens. As respostas `204` devem ter corpo vazio. Valores só são capturados em respostas de sucesso com os campos esperados.

## Variáveis preservadas

| Variável | Uso |
|---|---|
| `email`, `password`, `displayName` | Credenciais e nome usados no cadastro e login. Email único gerado por padrão; senha de exemplo aleatória gerada somente quando vazia. |
| `newUserOnRun` | `true` gera novo email no cadastro. Para uma conta existente, execute Login diretamente, sem executar Cadastro novamente. |
| `userId` | UUID retornado em `id` pelo cadastro; a API não retorna campo `eid`. |
| `accessToken`, `refreshToken`, `tokenExpiresAt` | Login e refresh atualizam os tokens; Bearer herdado da collection nas requests protegidas. Expiração em milissegundos Unix. |
| `amount`, `currency`, `paymentDescription` | Dados de exemplo do pagamento. Use moeda válida em maiúsculas e valor positivo. |
| `idempotencyKey`, `paymentId` | Chave da criação principal e ID usado em replay, consulta e confirmação. |
| `cancelPaymentId` | Segundo pagamento, exclusivo do fluxo de cancelamento. |
| `notificationId`, `notificationPoll` | ID capturado da notificação e contador de polling limitado. |
| `runFraudReview` | `false` pula a pasta 06 no Runner/Newman. |
| `reviewerAccessToken` | Token de outro usuário com `ADMIN` ou `FRAUD_REVIEWER`. |
| `heldPaymentIdApprove`, `heldPaymentIdReject` | Dois pagamentos distintos em `UNDER_REVIEW`, pertencentes a outro usuário. |

As requests públicas usam No Auth; as protegidas herdam Bearer da collection. A pasta 06 possui Bearer próprio de revisor. Não existe endpoint público de promoção de roles: obtenha o token e os pagamentos retidos no setup de testes autorizado. Para habilitar a pasta, preencha suas três variáveis e defina `runFraudReview=true`. Uma pasta pulada não constitui evidência de autorização/revisão validada.

Em Send isolado, o polling não encadeia requests; use Runner/Newman para aguardar entrega eventual. A aplicação mantém JWTs de acesso válidos até a expiração após logout; a família de refresh é revogada. As variáveis ficam disponíveis para inspeção, mas o refresh salvo deixa de ser utilizável. Execute Login novamente para iniciar outra sessão.

## Newman

Com Newman instalado, execute da raiz do repositório:

```bash
rtk proxy newman run src/test/postman/payments-v1.postman_collection.json --delay-request 250 --timeout-request 10000 --timeout-script 5000
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
- Execute a pasta opcional somente com os pré-requisitos; confirme os dois estados e a separação entre proprietário e revisor.
- `rtk proxy bash scripts/validate-docs.sh` e `rtk git diff --check`.
