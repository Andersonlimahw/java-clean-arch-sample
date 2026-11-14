---
title: Validação real da collection Postman
status: verified
updated: 2026-11-13
related: [../postman.md, ../testing.md]
---

# Validação real da collection Postman

Execução em 2026-11-13, aproximadamente 23:28–23:32, America/Sao_Paulo. Worktree `refactor-expert`, base Git `7095453`, incluindo as alterações locais de refactor. Nenhuma execução em produção.

## Ambiente e método

- Java 25.0.1, Maven 3.9.11, Newman 6.2.3 via cache do npm.
- API real em `http://127.0.0.1:8080`; PostgreSQL 16, Redis 7, RabbitMQ 3.13 e WireMock 3.13.2 locais.
- `rtk mvn -B -DskipTests package`: BUILD SUCCESS; esse comando compila/empacota, não executa testes Java.
- Dependências iniciadas com `rtk proxy docker-compose up -d postgres redis rabbitmq fraud-screening`; o binário standalone funciona neste host, onde o subcomando `docker compose` não estava disponível.
- API iniciada a partir do JAR do worktree com perfil `local`, credenciais locais de exemplo e chave JWT efêmera.
- Collection carregada sem alteração de requests ou assertions pela API programática `newman.run`, com reporters desabilitados e relatório reduzido sem corpos, senhas ou tokens. Limites: request 10s, script 5s; intervalo 250ms.
- O fluxo principal foi executado duas vezes com sucesso. A tabela registra o último run principal e o run separado da pasta opcional.

## Resultados

| Escopo | Requests HTTP da collection | Assertions | Falhas |
|---|---:|---:|---:|
| Fluxo principal, pastas 01–05 e 07 | 27 | 94 | 0 |
| Revisão de fraude, pasta 06 | 3 | 11 | 0 |
| Total dos exemplos | 30 | 105 | 0 |

Os três exemplos opcionais são pulados por padrão; foram executados separadamente com pré-requisitos reais preparados. Não foram contabilizados como sucesso enquanto pulados.

Verificado em runtime: `email`, `password`, `userId`, `accessToken`, `refreshToken`, `paymentId`, `cancelPaymentId` e `notificationId` preenchidos e disponíveis no escopo da collection. Login, refresh e requests protegidas reutilizaram os valores. O cadastro gerou outro email no segundo run. A criação e seu replay retornaram o mesmo pagamento; confirmação retornou `COMPLETED`, cancelamento retornou `CANCELLED` e notificação referenciou o pagamento e seu proprietário. Erros esperados retornaram `400`, `401`, `403`, `404` e `409` com payload validado.

## Preparação da revisão de fraude

Foram cadastradas duas contas de teste adicionais: proprietário e revisor. Somente a conta recém-criada do revisor recebeu temporariamente `FRAUD_REVIEWER` no PostgreSQL local. Um mapping temporário do WireMock, restrito ao UUID do proprietário de teste, retornou uma decisão não utilizável pelo adapter; a aplicação criou dois pagamentos em `UNDER_REVIEW`, sem depender de indisponibilidade global do provider.

O Newman executou listagem, aprovação e rejeição usando o bearer do revisor e dois IDs distintos. Após o run, consultas autenticadas do proprietário confirmaram `PENDING` e `CANCELLED`. O mapping criado foi removido, as sessões dos fixtures foram revogadas e a role do revisor foi restaurada para `USER`. Contas, pagamentos e eventos locais de teste foram preservados.

Ao concluir, a API e os quatro containers iniciados para esta execução foram parados. Os volumes locais preservam quatro usuários e seis pagamentos de teste; nenhum volume foi removido. Os scripts temporários de execução foram descartados; os relatórios reduzidos foram entregues sem valores de credenciais.

## Correção documental encontrada

A collection e a API não apresentaram falhas nos cenários executados. Um probe separado de persistência na readiness configurou uma variável inicialmente `initial`, alterou para `updated` via `pm.collectionVariables.set` e usou `exportCollection`. Newman 6.2.3 exportou `initial`. A documentação que recomendava `--export-collection` para preservar as alterações foi corrigida em [Postman](../postman.md).

O relatório inicial também consultava `summary.collection.variables`, que representa os valores de entrada nessa versão. O coletor temporário foi corrigido para inspecionar o escopo de execução antes de afirmar que as variáveis estavam preservadas. Esse ajuste não alterou a collection nem seus testes.

## Limites

- A persistência da interface do Postman não foi exercitada; a evidência de execução é do Newman contra HTTP real.
- O serviço de tracing OTLP em `localhost:4318` não foi iniciado; houve erro de exportação de spans no log. Isso não causou falhas nas requests; não é evidência de tracing validado.
- Não foi necessário alterar código Java. O gate completo `mvn clean verify` não foi reexecutado neste trabalho; os resultados anteriores de outra tarefa não são contados aqui.
- Testes de corrida, carga, fraude explicitamente recusada (`422`) e todos os cenários Java de integração estão fora desta execução dos exemplos da collection.

## Eval

- Reexecutar fluxo principal no Runner/Newman contra a stack local e exigir zero falhas de assertions e scripts.
- Executar a pasta 06 com revisor separado e dois pagamentos retidos; validar também os estados pelo proprietário.
- Verificar preenchimento do escopo de execução sem imprimir valores de credenciais.
- Revalidar a persistência se a versão do Newman mudar; não inferir resultado do export apenas a partir do sucesso HTTP.
