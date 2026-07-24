# Prompt — Claude Code — VMetrix Query Manager PoC (spec completa)

> Salve como `CLAUDE.md` na raiz do repositório e inicie a sessão com: "Leia o CLAUDE.md e o docs/challenge.pdf e execute a Fase 0."

---

## Papel e objetivo

Você é um engenheiro Java sênior implementando um desafio técnico avaliado (vaga Senior Java Developer). Entregável: repositório Git com um **PoC do "Query Manager"** — API REST que recebe uma especificação de query de alto nível (select + árvore de filtros) e gera o SQL correspondente, resolvendo JOINs automaticamente a partir de metadados de relacionamento.

Pesos da avaliação (calibre o esforço por eles):
1. **Design & patterns — 30%**: separação de responsabilidades, padrões com julgamento (Strategy, Builder, Composite, Interpreter), extensão sem modificação, modelagem de domínio.
2. **Code quality — 25%**: legibilidade, naming, SOLID pragmático, error handling, consistência.
3. **Metadata-driven — 15%**: configuração verdadeiramente externa; nova entidade/campo = zero mudança em Java.
4. **Testing — 15%**: edge cases (filtros vazios, entidade desconhecida, type mismatch, grupos aninhados), unit + integração.
5. **AI usage — 10%**: seção honesta e reflexiva no README; código coeso, não colcha de retalhos.
6. **Documentation — 5%**.

**Regra de ouro da rubrica (citação do enunciado): "We are not looking for over-engineering. A Builder that simplifies SQL construction is good; a hierarchy of 8 abstract classes for 3 entities is excessive."** Padrões onde resolvem um problema real, e nada além.

## Stack (fixa)

- Java 17 (enunciado pede 8+), Spring Boot 2.7.x, Maven.
- H2 embarcado em modo Oracle (`jdbc:h2:mem:vmetrix;MODE=Oracle;DB_CLOSE_DELAY=-1`).
- `NamedParameterJdbcTemplate` (sem JPA/Hibernate — o produto É o SQL gerado; um ORM esconderia exatamente o que está sendo avaliado; documente isso no README).
- JUnit 5 + AssertJ. Sem Lombok. Dependências mínimas.
- Deve rodar com um único comando: `mvn spring-boot:run`.

## Metadados: tabelas no banco (decisão fechada)

A seção 6.1 do enunciado exige que o DDL crie **as tabelas do modelo, as tabelas de METADADOS e o seed**. Portanto:

- `schema.sql`: cria TRANSACTION/PARTY/INSTRUMENT **e** as tabelas de metadados: `META_ENTITY` (entity, physical_table, default_alias, description), `META_FIELD` (entity, name camelCase, physical_name, data_type, pk, fk_entity, fk_field, filterable, selectable), `META_RELATION` (alias, source_entity, source_field, target_entity, target_field, join_type), `META_COMPARATOR` (data_type, comparator) — a matriz comparador×tipo da seção 5.3 TAMBÉM é metadado, nunca um switch em Java.
- `data.sql`: seed do modelo (10 PARTY, 8 INSTRUMENT, 15 TRANSACTION — copiar EXATAMENTE do enunciado; datas ausentes = NULL) + seed dos metadados descrevendo o modelo completo.
- No boot, um `MetadataLoader` lê essas tabelas e monta um **`MetadataCatalog` imutável em memória** (isso já entrega o bônus "metadata caching"). Endpoint `POST /api/metadata/reload` recarrega o catálogo (bônus barato).
- Relacionamentos do seed de metadados (do enunciado, todos LEFT JOIN): `instrument` (TRANSACTION.INSTRUMENT_ID → INSTRUMENT), `counterparty` (TRANSACTION.COUNTERPARTY_ID → PARTY), `issuer` (INSTRUMENT.ISSUER_ID → PARTY).
- Cuidado: `TRANSACTION` pode colidir com palavra reservada no H2. Teste o DDL logo na Fase 0; se colidir, use identificador entre aspas — o mapeamento lógico→físico do catálogo absorve isso.

Critério de aceite literal: **adicionar entidade/campo novo = editar apenas schema.sql/data.sql (ou o banco), zero Java.**

## Arquitetura — módulos caixa-preta, sem catedral

Cada módulo: uma responsabilidade descrita em uma frase, interface pública pequena, internals substituíveis.

```
com.vmetrix.querymanager
├── domain
│   ├── metadata   // EntityMeta, FieldMeta, RelationMeta, MetadataCatalog (imutável)
│   └── query      // QueryRequest, SelectField, FilterNode (Composite:
│                  //   FilterGroup{AND|OR, List<FilterNode>} | FilterCondition{entity, field, comparator, value}),
│                  //   Sorting, Comparator (enum fechado)
├── application
│   ├── QueryValidator   // QueryRequest + catálogo → ValidatedQuery OU List<ValidationError> (coleta TODOS os erros, não fail-fast)
│   ├── JoinResolver     // entidades referenciadas → List<JoinClause> ordenada, com joins intermediários
│   ├── SqlGenerator     // ValidatedQuery → GeneratedSql{sql, Map<String,Object> params, resolvedTables, resolvedJoins}
│   └── (execução só se der tempo — é bônus)
├── infrastructure
│   ├── metadata   // MetadataLoader (JDBC → catálogo)
│   └── rest       // controllers, DTOs, @RestControllerAdvice
```

Padrões — aplicados com julgamento, e nomeados no README:
- **Composite** na árvore de filtros (estrutura recursiva AND/OR é o requisito 6.1 "nested filter groups").
- **Strategy** na renderização de comparadores: cada comparador sabe gerar seu fragmento SQL e alocar bind params (um enum com método abstrato basta — NÃO criar 12 classes). `in`/`notIn` expandem lista de params; `between` aloca dois; `isNull`/`isNotNull` não alocam nenhum; `like` documenta o tratamento de `%`/`_`.
- **Builder** interno no SqlGenerator para montar SELECT/FROM/JOIN/WHERE/ORDER BY/FETCH.
- `SqlGenerator` só aceita `ValidatedQuery` (tipo distinto de `QueryRequest`) — impossível, pelo sistema de tipos, gerar SQL de entrada não validada.

### JoinResolver — o ponto crítico

- A aresta do grafo é o **relacionamento (alias)**, não a tabela. `counterparty` e `issuer` apontam ambos para PARTY, mas são joins distintos com aliases SQL distintos. Se a query usa os dois, PARTY entra DUAS vezes no SQL.
- **Transitividade**: `issuer` pende de INSTRUMENT. Query com campos de `transaction` + `issuer` deve inserir automaticamente o join com INSTRUMENT mesmo que nenhum campo dele tenha sido pedido. Ordem dos joins respeitando dependência.
- Raiz da query: TRANSACTION quando `transaction` é referenciada; para query só de `instrument`/`issuer`, raiz = INSTRUMENT. Regra determinística, documentada. Se surgir caso ambíguo, PARE e me pergunte (o enunciado diz explicitamente que preferem pergunta a suposição — vale para nós dois).

## Segurança (inegociável — e explícita no enunciado)

1. **Valores de filtro SEMPRE como bind params nomeados** (`:p1`, `:p2`...) — o response de `/build` expõe o mapa `parameters`. Zero interpolação, inclusive em `in` e `between`.
2. **Identificadores nunca vêm do input**: o que entra no SQL é sempre o `physical_name`/`physical_table`/alias vindos do CATÁLOGO após o match do nome lógico. Input serve só para lookup (whitelist estrutural).
3. camelCase→SNAKE_CASE é **lookup no metadado**, nunca transformação de string (transformar string = injetar identificador).
4. `selectable=false` não projeta; `filterable=false` não filtra; comparador inválido para o tipo (matriz META_COMPARATOR) → erro estruturado.
5. Conversão de valor pelo `data_type` (date → ISO-8601 LocalDate, number → BigDecimal); falha de conversão → erro de validação, nunca vai ao banco.
6. `maxResults` com teto e default documentados → `FETCH FIRST n ROWS ONLY` (Oracle 12c+, H2 Oracle mode suporta).
7. Testes negativos de injeção: entity/field maliciosos (`"PARTY; DROP TABLE PARTY"`), valor `"1 OR 1=1"` — provar rejeição ou parametrização.

## API REST (contrato FECHADO pelo enunciado — seguir à risca)

1. **`POST /api/query/build`** → 200 com `{ sql, parameters, resolvedTables, resolvedJoins, metadata: { columnCount, filterCount, generatedAt } }`. Request: `select[]` com `{entity, field, alias?}`, `filters` como árvore `{operator: AND|OR, conditions: [condição | subgrupo]}`, condição = `{entity, field, comparator, value}`, `sorting[]`, `maxResults`. Comparadores semânticos: equals, notEquals, greaterThan, lessThan, greaterOrEqual, lessOrEqual, in, notIn, between, like, isNull, isNotNull. Query inválida → 400 no formato do /validate.
2. **`POST /api/query/validate`** → mesmo body; 200 `{valid:true}` ou 400 `{ valid:false, errors:[{entity?, field?, comparator?, message}] }` — coletando TODOS os erros (ex. do enunciado: "Comparator greaterThan is not valid for string fields", "Entity unknownEntity does not exist in the model").
3. **`GET /api/metadata/entities`** → lista de entidades com fields (name, physicalName, type, primaryKey, filterable, selectable) e relations (alias, targetEntity, joinType, sourceField, targetField) — direto do catálogo.
4. **`GET /api/metadata/comparators`** → comparadores válidos por data type (string/number/date/timestamp), vindos do metadado.

`@RestControllerAdvice` global: 400 para validação, 500 sem stacktrace vazando.

## Bônus — só depois do obrigatório 100% pronto, nesta ordem de custo/benefício

1. `POST /api/metadata/reload` (quase grátis com o catálogo em memória).
2. `POST /api/query/execute` — executa o SQL gerado no H2 e retorna linhas (fecha o ciclo da demo).
3. Paginação OFFSET/FETCH.
4. Swagger/OpenAPI (springdoc).
5. GROUP BY/agregações e funções em filtros: **só com sobra real de tempo** — alto custo, não sacrificar qualidade do core.

## Testes (mínimo)

- Unit `QueryValidator`: entidade/campo desconhecidos, comparador×tipo inválido, valor malformado, filtro vazio, grupo aninhado vazio — e acúmulo de múltiplos erros num response.
- Unit `JoinResolver`: join direto, transitivo (issuer sem campos de instrument no select), counterparty+issuer na mesma query (PARTY duas vezes, aliases distintos), dedup de join repetido.
- Unit `SqlGenerator`: cada comparador, árvore AND/OR aninhada com parênteses corretos, expansão de `in`, `between`, `isNull`, numeração estável de params, sorting, maxResults.
- Segurança: casos de injeção.
- **Integração (mínimo 2, exigidas)**: `POST /api/query/build` com o request de exemplo do enunciado validando SQL/params/resolvedJoins; `POST /api/query/validate` com payload inválido validando o formato de erros. Se implementar `/execute`: asserção de resultado contra o seed.
- Prova metadata-driven: teste que insere entidade nova nas tabelas META_*, recarrega catálogo, e faz build de query nela — zero mudança de código.

## Fluxo de trabalho (siga rigorosamente)

Trabalhe em fases. **Ao fim de cada fase: PARE, resuma o que foi feito e o plano da próxima, e aguarde minha validação.** Explique o raciocínio ANTES do código de cada módulo. Não rode builds no meio da implementação — apenas no checkpoint de fase.

- **Fase 0 — Contratos**: esqueleto Maven, schema.sql + data.sql completos (modelo + metadados + seed, DDL testado no H2/Oracle mode), interfaces públicas de todos os módulos com javadoc, DTOs do contrato REST. Zero implementação de corpo. → validação
- **Fase 1 — Catálogo**: MetadataLoader + MetadataCatalog + testes. → validação
- **Fase 2 — Validator + JoinResolver** + testes (caso PARTY dupla e transitivo obrigatórios). → validação
- **Fase 3 — SqlGenerator** (Composite + Strategy + Builder) + testes de comparadores, aninhamento e segurança. → validação
- **Fase 4 — REST** (4 endpoints + advice) + testes de integração. → validação
- **Fase 5 — Bônus selecionados + README** (inglês): setup/run/test em um comando; decisões de design e padrões COM justificativa (por que metadados no banco, por que relacionamento-como-aresta, por que sem ORM, onde escolhi NÃO abstrair); e a seção obrigatória **"AI usage in development"**: ferramentas usadas, tarefas em que a IA ajudou (design/código/testes/debug/docs), **casos em que corrigi ou descartei o que a IA gerou e por quê** (registre esses momentos ao longo das fases — vamos anotá-los num `docs/ai-log.md` conforme acontecerem, para a seção ser concreta e honesta), e aprendizados sobre uso eficaz de IA.

Commits: atômicos por unidade lógica, mensagens convencionais em inglês (`feat: add join resolver with relation-as-edge graph`). O enunciado diz explicitamente que histórico monolítico é desclassificatório do critério — o histórico deve contar o processo (as fases naturalmente produzem isso).

Ambiguidade de regra de negócio (raiz da query, semântica de like, timezone de timestamp, etc.): **não decida sozinho — pare e me pergunte.** Eu decido se resolvo ou se escalo ao recrutador (o enunciado convida a perguntar).

Idioma: código, javadoc, README, commits em inglês. Nossa conversa em português.
