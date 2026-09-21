# Especificação — Maestro AI Android v1

Ordem do operador em 21/09/2026: *"Vamos iniciar o desenvolvimento do Maestro AI
(Android) nas mesmas bases e diretrizes da Calculadora Financeira (Android)."*
Este documento registra **o que foi decidido e por quê**, para que nenhuma
escolha precise ser redescoberta ou relitigada.

Toda verificação de documentação de provedor citada aqui foi feita em
**21/09/2026**, na documentação oficial do próprio provedor, e não de memória.

## 1. Do que se trata

O Maestro AI é uma **mesa de redação deliberativa**: o usuário dá um título, um
texto de partida e um protocolo; seis modelos de fornecedores diferentes
escrevem e revisam o mesmo artefato em rodadas, até convergirem ou até estourar
um teto de custo ou de tempo. Cada turno vira um artefato versionado, com
relatório, *diff* e auditoria de links.

O Maestro AI Android é o **port nativo** desse produto, como a
`calculadora-android` foi o port da `calculadora-app`.

**A fonte do porte é o Maestro AI web**, dentro do `admin-app` — não o
`maestro-app`. Existem duas versões do Maestro; a de Windows é Tauri 2 + React
19 + Rust, com modo CLI que gera processos locais (`claude`, `codex`, `gemini`),
e nada disso existe num Android. Confirmado pelo operador em 21/09/2026: *"O
único app que tem versão desktop é o maestro-app. Todos os outros são web."*

**Port, não clone.** O aplicativo reimplementa as *lógicas* do produto web em
Kotlin novo. Não empacota o produto existente, não carrega webview e não executa
código do web por baixo.

### Uma diferença de partida em relação à calculadora, dita agora

A calculadora foi portada de um produto web que funcionava; o produto web era a
especificação executável, e bastava medi-lo. Aqui não é assim. O operador
declarou em 21/09/2026 que o Maestro AI web *"nunca funcionou"* nas suas últimas
tentativas de uso, e adiou a correção dele para outra ocasião.

**Consequência metodológica, e ela governa o documento inteiro:** o código web é
a fonte do *escopo* e das *regras*, mas **não é referência de comportamento
comprovado**. Onde a calculadora podia dizer "o Android deve produzir o mesmo
número que o web produz", aqui o critério de aceite tem de ser o protocolo
escrito e o teste, nunca a paridade com uma execução do web. Isso é registrado
como risco aceito na seção 10, não escondido.

## 2. Escopo

Entra **tudo o que o módulo web faz**, com as exclusões nomeadas ao final desta
seção. Decisão do operador em 21/09/2026: paridade com o web.

### 2.1 A origem, medida antes de propor

| Peça | Linhas |
| --- | --- |
| `src/modules/maestro-ai/MaestroAiModule.tsx` | 1.443 |
| `admin-motor/src/handlers/routes/maestro-ai/sessions.ts` | 4.705 |
| `admin-motor/src/handlers/routes/maestro-ai/content-lock.ts` | 526 |
| **Total** | **6.674** |

Para comparar: a calculadora eram ~1.300 linhas. **Cinco vezes maior.** Isso
precisa estar dito desde o começo, porque muda o tamanho das entregas.

### 2.2 `sessions.ts`, unidade por unidade

As faixas de linha são as fronteiras reais das funções no arquivo, não
estimativas.

| Unidade | Linhas | Destino no Android |
| --- | --- | --- |
| Tipos de domínio e saneamento de entrada (6–424) | 419 | porta |
| Esquema do D1 e migrações — `ensureSchema` (425–590) | 166 | vira Room |
| Configurações, taxas, modelos e agentes ativos (591–701) | 111 | porta |
| Projeção pública de sessão e artefato (702–758) | 57 | vira modelo de UI |
| Artefato em markdown e versionamento (759–842) | 84 | porta |
| Guarda de segredo — Cloudflare Secret Store (843–963) | 121 | **substituída** — Keystore, seção 6 |
| Custo estimado e observado (964–989) | 26 | porta, em `BigDecimal` |
| Tempo de sessão e tetos (990–1007) | 18 | porta |
| Protocolo: leitura e validação do relatório do agente (1008–1628) | 621 | porta |
| Auditoria de links e defesa de SSRF (1629–1935) | 307 | porta, com o modelo de ameaça invertido — seção 5.4 |
| Auditoria do candidato a release final (1936–1984) | 49 | porta |
| Montagem dos prompts de rascunho e revisão (1985–2210) | 226 | porta |
| Rede: timeout, retry, tratamento de 429 (2211–2378) | 168 | porta |
| Resolução de modelo e cliente Vertex (2379–2462) | 84 | **substituída** — API geral, seção 2.4 |
| Chamada aos provedores e montagem do corpo (2463–2772) | 310 | **reescrita** nas APIs novas — seção 5 |
| Persistência da sessão (2773–2877) | 105 | vira Room |
| Estado da revisão circular e retomada (2878–3186) | 309 | porta |
| `runSession` — a orquestração (3187–4146) | 960 | porta; é o coração do produto |
| Rotas HTTP e varredura de sessões velhas (4147–4705) | 559 | **desaparece** — seção 2.4 |

`content-lock.ts` (526 linhas) porta inteiro: é segmentação de blocos
editoriais, manifesto com SHA-256 por bloco e validação de que uma revisão só
alterou os blocos que declarou ter alterado. É lógica de texto pura, sem
Android e sem rede — e por isso é o primeiro candidato a viver num módulo
testável na JVM.

### 2.3 O cliente web

`MaestroAiModule.tsx` (1.443 linhas) tem 31 unidades de estado, 5 efeitos e 20
chamadas de backend contra quatro rotas. Os tipos de domínio são nove:
`AgentRate`, `AgentSettings`, `MaestroSettings`, `MaestroEvent`,
`LinkAuditItem`, `MaestroSession`, `MaestroArtifactSummary`,
`MaestroArtifactDetail` e `ApiTestResult`. São seis agentes e **cinco abas de
artefato**: texto, *diff*, relatório, links e meta.

As 20 chamadas de backend somem: no Android não há backend a chamar. O que era
`fetch` vira chamada a um caso de uso local, e o que era *polling* de
`GET /sessions/{id}` vira observação de um `Flow` do Room.

### 2.4 O que sai, e por quê

Nenhuma unidade fica sem destino declarado. Unidade não mencionada vira
esquecimento em auditoria futura.

| Unidade | Decisão | Motivo |
| --- | --- | --- |
| Rotas HTTP (`handleMaestroAi*`) | **sai** | São a fronteira do Worker. Um aplicativo que orquestra localmente não tem cliente remoto a atender. |
| `runMaestroSweep` / `sweepStaleSessions` | **muda de forma** | No web é um *cron* do Worker que mata sessão travada. Sem servidor não há *cron*: quem reconcilia é a abertura do aplicativo — ver seção 4.3. |
| Cloudflare Secret Store (`cloudflareRequest`, `listSecretStoreSecrets`, `upsertSecretStoreSecret`) | **substituída** | A chave passa a viver no aparelho, cifrada pelo Android Keystore. Decisão do operador, seção 6. |
| `vertexClient`, `resolveVertexModel`, `VERTEX_GEMINI_CANDIDATES` | **substituída** | O web fala Gemini por **Vertex AI**, que exige projeto no GCP. Decisão do operador em 21/09/2026: *"Vertex necessita de cadastro no GCP. Não é o mais adequado para um produto geral. Use a API mais geral."* |
| *Rate limiting* e lista de origens permitidas | **sai** | Preocupação de servidor multiusuário. Aqui o único cliente é o dono do aparelho, gastando a própria chave. |
| Auditoria de links / defesa de SSRF | **porta, com o modelo de ameaça invertido** | Ver seção 5.4: não sai, mas o motivo de existir muda. |

## 3. Herança da frota: o que não se redescobre

A `calculadora-android` já pagou por estas lições. Elas entram por herança, com
a evidência de origem, e **não voltam a ser investigadas**.

- **`java-kotlin` continua fora do CodeQL.** Não por falta de Kotlin, mas porque
  o CodeQL não suporta o Kotlin 2.4.20 da frota — medido em 18/09/2026 e
  rastreado pela CALANDR-14. O `quality/code-quality-probe.js` permanece como
  *placeholder* e é a única fonte JavaScript do repositório.
- **`androidx.security:security-crypto` está morto.** Todas as APIs foram
  depreciadas em 1.1.0-beta01 (04/06/2025) *"in favour of existing platform APIs
  and direct use of Android Keystore"*, e assim seguem no estável 1.1.0. Não
  haverá mais versões. `EncryptedSharedPreferences` e `EncryptedFile` estão
  fora — ver seção 6.
- **A esteira de publicação já está em paridade** desde a PANDROI-40:
  `publish-play.yml` com notas de versão em `play/release-notes/pt-BR.txt` e
  `record-play-release.yml` para gravar a Release de uma versão já publicada.
  Um aplicativo nunca publicado é *draft app* e só aceita `status: draft` na
  trilha pública — a primeira publicação se conclui no Play Console.

### 3.1 A linha de base do Gradle precisa subir, e isso é parte da v1

Medido em 21/09/2026 neste repositório: `compileSdk = 36`, `targetSdk = 36`,
`minSdk = 24`, sem `gradle/libs.versions.toml`, sem Kotlin Gradle Plugin, só o
módulo `:app`, zero arquivos `.kt`. A calculadora está em
`compileSdk`/`targetSdk` 37, `minSdk` 34 e catálogo de versões.

A primeira entrega de código traz, na mesma mudança:

1. `compileSdk` e `targetSdk` 37, `minSdk` 34.
2. `gradle/libs.versions.toml` com AGP, KGP, Compose e demais versões.
3. O Kotlin Gradle Plugin declarado — **e a linha `kotlin-gradle-plugin`
   removida do `ignore` do [`.github/dependabot.yml`](../.github/dependabot.yml)
   na mesma mudança.** O próprio arquivo carrega essa instrução inline: *"a
   linha do kotlin-gradle-plugin deve ser REMOVIDA assim que o KGP for declarado
   explicitamente no projeto"*. A partir daí ele é dependência direta e
   atualizável.
4. Um workflow `ci.yml` equivalente ao da calculadora — validação do wrapper,
   `assembleDebug`, `lintDebug` e testes unitários —, que hoje não existe aqui.

## 4. Arquitetura

```
:core:protocolo   Kotlin puro, sem Android — content-lock, leitura de relatório,
                  auditoria de release, montagem de prompts, custo
:core:provedores  os seis provedores sobre Retrofit/OkHttp, retry e uso
:core:sessao      Room, orquestração da deliberação, retomada
:app              Compose, WorkManager, Keystore
```

`:core:protocolo` **não depende de Android**, pela mesma razão que fez
`:core:calc` valer a pena na calculadora: ele concentra a parte que decide se um
turno é válido — 621 linhas de leitura de relatório mais as 526 do
*content-lock* —, e essa parte tem de poder ser executada na JVM, em
milissegundos, a cada mudança. É onde os testes rendem mais por linha.

### 4.1 A orquestração roda no aparelho, e isso precisa de mecanismo nomeado

No web, `runSession` é um Worker da Cloudflare que roda até
`max_runtime_minutes`. Num telefone não existe equivalente: processo de
aplicativo morre, e o sistema tem regras sobre o que pode continuar rodando.

**Mecanismo escolhido: `CoroutineWorker` do WorkManager com `setForeground()`.**
É o caminho oficial documentado para trabalho longo iniciado pelo usuário:
*"WorkManager supports long-running workers that can execute for longer than 10
minutes by managing a foreground service on your behalf, along with a
configurable notification."* Exige, em quem tem `targetSdk` 34+, declarar o tipo
do serviço em dois lugares — no manifesto, fundindo `foregroundServiceType` em
`androidx.work.impl.foreground.SystemForegroundService` com
`tools:node="merge"`, e em tempo de execução, no `ForegroundInfo`.

**Tipo declarado: `dataSync`**, com as permissões `FOREGROUND_SERVICE` e
`FOREGROUND_SERVICE_DATA_SYNC`. É o tipo cuja definição oficial cobre *"transfer
data between a device and the cloud over a network"*, que é exatamente o que uma
deliberação entre seis provedores faz. `shortService` está descartado: tem
timeout de três minutos, e uma sessão do Maestro dura muito mais.

Duas restrições documentadas entram como limite conhecido, não como surpresa:
aplicativo que mira o Android 15 ou superior não pode subir serviço `dataSync` a
partir de um receptor de `BOOT_COMPLETED`; e, a partir do Android 16, *worker*
longo com serviço em primeiro plano pode esgotar a cota de *jobs* do aplicativo.
Nenhuma das duas atinge a v1, porque a sessão é sempre iniciada por toque do
usuário com o aplicativo aberto.

A notificação em primeiro plano é obrigação técnica, mas também é a superfície
honesta do produto: mostra a rodada atual, o agente da vez, o custo acumulado e
uma ação de cancelar.

### 4.2 Room no lugar do D1

O esquema do D1 (166 linhas de `ensureSchema`) vira entidades do Room: sessão,
artefato e configurações. O que no web é `GET /sessions/{id}` em *polling* vira
`Flow` observado pela interface; o *worker* escreve, a tela lê, e não há
serialização de eventos indo e voltando por HTTP.

O diário da sessão (`events_json`) porta como está — lista serializada com
leitura estrita. O web já trata jornal corrompido como falha explícita
(`paused_resume_state_invalid`), e essa disciplina fica.

### 4.3 Morte de processo é o novo timeout de Worker

No web, sessão travada é resolvida por um *cron* que varre sessões velhas. Sem
servidor, o gatilho muda: **na abertura do aplicativo, toda sessão em `running`
sem `WorkInfo` vivo é reconciliada.** O WorkManager é a fonte da verdade sobre
"existe execução viva"; o Room é a fonte da verdade sobre "onde a deliberação
parou".

A retomada já existe no web e porta inteira (309 linhas de estado circular):
ela reconstrói o ponto da rodada a partir dos artefatos aceitos e do jornal. O
que muda é apenas quem a dispara.

## 5. Os seis provedores

Decisão do operador em 21/09/2026: **sempre os modelos mais novos de cada
provedor, com os recursos novos das APIs**, conferidos na documentação oficial.
E **sem tiers Flash, sem exceção** — uma exceção para a DeepSeek foi aberta e
revogada no mesmo dia, depois que o changelog oficial mostrou que o V4 Pro não
foi descontinuado: *"In response to user demand, we have decided to continue
providing API services for DeepSeek V4 Pro after September 14, 2026."*

### 5.1 O quadro, verificado em 21/09/2026

| Agente | Modelo | Transporte | Controle de raciocínio |
| --- | --- | --- | --- |
| `claude` | `claude-fable-5-1` | `POST https://api.anthropic.com/v1/messages` | `thinking: {type: "adaptive"}` + `output_config.effort` |
| `codex` | `gpt-6-astra` | `POST https://api.openai.com/v1/responses` | `reasoning.effort`: `low`…`max` |
| `gemini` | `gemini-3.1-pro-preview` | `POST https://generativelanguage.googleapis.com/v1beta2/interactions` | `generation_config.thinking_level` |
| `deepseek` | `deepseek-v4-pro` | `POST https://api.deepseek.com/chat/completions` | — |
| `grok` | `grok-4.7` | `POST https://api.x.ai/v1/responses` | `reasoning.effort` |
| `perplexity` | `perplexity/sonar-reasoning-pro` | `POST https://api.perplexity.ai/v1/agent` | `reasoning.effort`: `minimal`…`max` |

Três escolhas são do operador — `claude-fable-5-1`, `gemini-3.1-pro-preview` e o
uso da API geral do Gemini. As outras decorrem da regra: `gpt-6-astra` é o
modelo de raciocínio corrente da OpenAI, `grok-4.7` é o Grok corrente, e
`deepseek-v4-pro` é o único não-Flash que a DeepSeek oferece.

**Perplexity, uma linha de justificativa.** O peer é `sonar-reasoning-pro` e não
`sonar-deep-research`. A recusa é por **forma**, não por capacidade: *Deep
Research* é um produto de pesquisa longa, que devolve relatório após minutos, e
não um interlocutor de turno numa deliberação. O `sonar` simples está barrado
pela regra de não usar tier reduzido. O pin `perplexity/kimi-k3` que existe no
cross-review é decisão daquele produto e **não** se transporta para cá.

### 5.2 Os três recursos de API que mudaram e precisam entrar

Não são detalhes de implementação: cada um invalida o jeito antigo de chamar.

1. **Anthropic — `budget_tokens` morreu.** Nos modelos correntes o campo é
   rejeitado com HTTP 400. O caminho é `thinking: {type: "adaptive"}` com
   `output_config.effort`.
2. **Google — a Interactions API substituiu o `generateContent`.** É *"the new
   standard for building with Gemini, recommended for all new projects"*; o
   `generateContent` segue suportado. Muda o endpoint (`/v1beta2/interactions`),
   `contents` vira `input`, o *streaming* passa a ser `"stream": true` no mesmo
   endereço em vez de um `:streamGenerateContent` separado, e o raciocínio vira
   `thinking_level` no lugar de `thinking_budget`. **Os dois juntos no mesmo
   pedido devolvem 400.**
3. **Perplexity — a Sonar Chat Completions morre em 27/09/2026.** A nota oficial
   é literal: *"Sonar Chat Completions is now Agent API. Sonar will be supported
   until September 27, 2026."* A sucessora é a Agent API em `POST /v1/agent`,
   com ids de modelo no formato `provider/model`, `input` no lugar de `messages`
   e saída tipada em passos no lugar de `choices`.

### 5.3 Um transporte só para os seis, e a medição que levou a isso

Nenhum dos seis provedores publica SDK oficial **para Android**. Medido em
21/09/2026: o `anthropic-sdk-java` não menciona Android e compila para bytecode
JVM 1.8; o `google-genai` para Java não menciona Android (depende de OkHttp
4.12.0, então ao menos não é barrado por `java.net.http`); o `openai-java` cita
Android uma única vez, e apenas para dizer que publica regras de *keep* para
ProGuard/R8 — o que não é declaração de suporte. xAI, DeepSeek e Perplexity não
publicam SDK Java nenhum.

Decisão: **Retrofit/OkHttp contra os contratos REST documentados, para os
seis.** Os contratos REST são a interface oficial e documentada de todos eles,
o que mantém a diretriz de usar solução oficial; e um transporte só significa uma
abstração de *streaming*, uma taxonomia de erro e uma contabilidade de custo,
testadas uma vez em vez de seis. Misturar um SDK que não afirma suportar Android
com cinco clientes REST seria carregar peso, risco de R8 e uma segunda linha de
inventário em [`THIRDPARTY.md`](../THIRDPARTY.md) para não ganhar nada.

Isto é afastamento declarado do padrão da *skill* `claude-api`, que manda usar o
SDK Java oficial em projetos Kotlin. O padrão supõe JVM de servidor; aqui o alvo
é Android, e o próprio SDK não afirma suportá-lo.

### 5.4 A auditoria de links porta, com o modelo de ameaça invertido

O web audita cada URL que o texto cita e recusa endereço privado, `localhost` ou
IP interno — 307 linhas de defesa contra SSRF. Faz sentido lá: o Worker é
infraestrutura da LCV, e um modelo que cite `http://169.254.169.254` faria o
servidor buscar credencial de metadados.

No aparelho isso se inverte. Não há metadados de nuvem a vazar, mas há **a rede
doméstica do usuário**: um `http://192.168.0.1` auditado a partir do telefone é
o aplicativo varrendo o roteador de quem o instalou. A unidade porta inteira —
`isPrivateIpv4`, `embeddedIpv4FromIpv6`, `isBlockedAuditHost` e a resolução
prévia de host —, e o motivo de existir passa a ser **proteger a rede do
usuário**, não a nossa. O comentário no código dirá isso; herdar a defesa sem
herdar a razão é como ela apodrece.

## 6. A chave é do usuário e fica no aparelho

Decisão do operador em 21/09/2026: *"Se as chaves não ficarem no aparelho,
ficariam onde? Não quero ser responsável pela guarda das chaves, que são
propriedade do usuário. Então, elas têm que ficar no aparelho do usuário."*

Isso é posição de produto, não limitação técnica: custódia de chave alheia é
responsabilidade que não se assume sem querer. E é o que torna a arquitetura da
seção 4 obrigatória — se o aplicativo falasse com um servidor nosso, a chave
teria de viajar até ele.

### 6.1 O desenho

`androidx.security:security-crypto` está fora, pelo motivo da seção 3. O caminho
oficial é o **Android Keystore direto**: chave AES gerada **dentro** do
Keystore, não exportável, usada para cifrar o segredo; o texto cifrado fica em
DataStore comum do aplicativo. O segredo em claro só existe em memória, durante
a montagem da chamada.

O que o Keystore garante, citado: *"Key material never enters the application
process... If the app's process is compromised, the attacker might be able to
use the app's keys but can't extract their key material."* Processo comprometido
consegue **usar** a chave, não **levar** a chave. Essa distinção é o que se pode
prometer ao usuário com honestidade, e é o que a tela de configurações vai
dizer — sem a palavra "seguro" solta.

### 6.2 Duas decisões que dependem do operador

Ambas têm custo real e nenhuma tem resposta na documentação; estão na seção 11
como pendências abertas.

1. **StrongBox** (`setIsStrongBoxBacked`), disponível desde a API 28 e portanto
   em todo aparelho que o `minSdk` 34 alcança. A documentação é explícita sobre
   o preço: *"Appropriate for applications requiring the highest level of
   security... However, it is slower, more resource-constrained, and supports
   fewer concurrent operations."* Nem todo aparelho tem o hardware, então exige
   caminho de degradação.
2. **Vínculo com autenticação do usuário**
   (`setUserAuthenticationParameters()`), em dois modos: autorizar por um tempo
   após a autenticação, ou autorizar cada operação. O segundo é biometria a cada
   chamada de provedor — proteção real e atrito real numa sessão com dezenas de
   chamadas.

A v1 assume `minSdk` 34, herdando a decisão do operador de 19/09/2026 na
calculadora. Isso torna as duas APIs acima universalmente disponíveis e dispensa
caminho por nível de API.

### 6.3 O que se declara na Play

A calculadora declarava coleta zero: as fontes eram públicas e sem chave. Aqui
não dá para herdar isso por omissão.

O aplicativo **não coleta** nada para a LCV: não há servidor nosso no caminho,
não há analytics, não há identificador persistente próprio. Mas o usuário
**envia o próprio texto a terceiros** — os seis provedores —, e isso é
compartilhamento com terceiro no vocabulário do formulário de Segurança de dados
da Play. A declaração tem de dizer exatamente isso, e a tela que pede a chave
tem de dizer a mesma coisa antes de a primeira sessão rodar.

### 6.4 `store: false` em todos os provedores que o oferecem

Medido em 21/09/2026, e é um achado que contradiz o próprio produto se ficar por
omissão:

- **OpenAI Responses:** `store` é `true` por padrão, com retenção de 30 dias.
- **Gemini Interactions:** `store` é `true` por padrão, com retenção de 55 dias
  no *tier* pago e 1 dia no gratuito.
- **Perplexity Agent API:** expõe `store`.

Um produto cuja premissa é "sua chave nunca sai do seu aparelho" não pode deixar
o texto do usuário retido por padrão em três provedores. **`store: false` é
explícito em todo pedido**, e não implícito.

Isso cobra dois preços, ambos aceitos. No Gemini, `store=false` é incompatível
com execução em segundo plano e impede `previous_interaction_id` — o que apenas
confirma a decisão de carregar o histórico localmente, que já era o desenho. Na
Perplexity, o modo assíncrono depende de `store`, e a medição de 17/09/2026
registrou que o caminho síncrono já estourou 40 s sem responder; o teto de
timeout por chamada precisa ser calibrado com isso em mente, e está na seção 11.

## 7. Aritmética: `BigDecimal`

Mesmo precedente da calculadora, pelo mesmo motivo: dinheiro em ponto flutuante
binário acumula erro, e aqui o número decide se a sessão para.

O web calcula custo em `number` — estimativa a partir do tamanho do prompt e do
teto de saída, depois substituída pelo custo observado quando o provedor devolve
contagem de tokens. As taxas são três por provedor: dólares por milhão de tokens
de entrada, por milhão de saída e, na Perplexity, por mil requisições de busca.

No Android tudo isso é `BigDecimal`, com escala e arredondamento declarados no
ponto de comparação com `max_cost_usd`. O teto de custo é a única barreira entre
uma sessão mal configurada e a fatura do usuário; ela não pode depender de
arredondamento implícito.

A contagem de tokens vem do provedor quando ele a devolve. Quando não devolve —
a Perplexity registra `usage: null` em resposta `incomplete` —, o custo cai para
a estimativa, e o evento do jornal diz qual dos dois foi usado. Custo estimado
apresentado como observado seria mentira barata.

## 8. Testes

Regra herdada da calculadora e que vale aqui inteira: **todo caso de teste tem
de poder falhar.** Teste que passa porque não exercita nada é pior que ausência
de teste, porque compra confiança sem entregá-la.

- **`:core:protocolo`, na JVM.** É onde está o maior retorno: 621 linhas de
  leitura de relatório e 526 de *content-lock* são funções puras sobre texto.
  Cada regra de validação ganha um par — uma entrada que passa e uma que falha.
  O `validateRevisionContentLock` em particular tem de ter caso em que a revisão
  mexe em bloco não declarado, e o teste **exige** a recusa.
- **`:core:provedores`, com servidor HTTP de mentira.** Um `MockWebServer` por
  provedor cobre: corpo montado conforme o contrato da seção 5.1, `store: false`
  presente, 429 com e sem `Retry-After`, timeout, e resposta sem `usage`.
  Nenhum teste fala com provedor real.
- **`:core:sessao`, com Room em memória.** Retomada depois de morte de processo
  é o caso que mais importa, e o teste o encena: grava estado no meio da rodada,
  destrói o *worker*, reabre, e verifica que a deliberação continua do ponto
  certo — não do começo, e não de um ponto adiante.
- **`:app`, instrumentado.** As telas com `testTag`, no padrão que a CALANDR-16
  provou: o teste digita, toca e lê, com ViewModel montado sobre falsos em
  memória, sem Hilt e sem rede.

Nenhum teste embute chave de API, nem sequer inválida com forma de chave real —
o *secret scanning* da frota não distingue chave falsa de chave vazada, e nem
deveria.

## 9. Privacidade e publicação

Decisões de produto vigentes, no mesmo espírito das da calculadora:

- zero analytics, *fingerprinting* ou identificador persistente próprio;
- nenhum servidor da LCV no caminho de dados do usuário;
- nenhuma telemetria do produto web migra;
- a chave de API é do usuário, fica no aparelho e nunca é exibida depois de
  gravada — a tela mostra "configurada" ou "não configurada", nunca o valor;
- o texto do usuário vai aos provedores que ele mesmo escolheu ativar, e a nada
  mais.

A publicação segue a esteira já em paridade (seção 3), com notas de versão em
`play/release-notes/pt-BR.txt` e o teto de 500 caracteres por idioma verificado
antes do build.

## 10. Riscos aceitos

1. **A origem não é referência de comportamento comprovado.** O produto web
   nunca funcionou nas tentativas recentes do operador (seção 1). O aceite é por
   protocolo escrito e teste, não por paridade com uma execução do web.
   Consequência prática: divergência entre Android e web **não** é, por si,
   defeito do Android.
2. **O custo é do usuário e o aplicativo o gasta sozinho.** Uma sessão aciona
   seis provedores em rodadas. O teto de custo e o de tempo são as únicas
   barreiras, e ambos dependem de contagem que o provedor devolve. Mitigação: a
   notificação em primeiro plano mostra o custo acumulado ao vivo, e o
   cancelamento é uma ação nela.
3. **Chave no aparelho é superfície nova.** O Keystore impede extração do
   material da chave, não o uso dela por processo comprometido (seção 6.1). Em
   aparelho com *root* a garantia é menor. Aceito: a alternativa é custódia pela
   LCV, que o operador recusou por razão de produto.
4. **Modelo em *preview*.** `gemini-3.1-pro-preview` é *preview*, e o nome pode
   mudar ou sair. O aplicativo tem de degradar com mensagem legível quando um id
   de modelo deixar de existir, em vez de falhar em silêncio — e nunca cair
   sozinho para um tier Flash, que está proibido.
5. **Seis provedores é seis contratos que mudam sozinhos.** Três mudanças
   incompatíveis já estão em curso agora (seção 5.2), e uma delas tem data: 27
   de setembro de 2026. Isso vai se repetir.

## 11. Pendências que esta especificação cria

Cada uma com estado e evidência. Lista que envelhece sem estado foi a falha
apontada na calculadora, e não se repete.

### Abertas, dependem de decisão do operador

1. **StrongBox: liga ou não?** Custo declarado pela documentação: mais lento,
   mais restrito em operações simultâneas, e nem todo aparelho tem o hardware —
   exige caminho de degradação. Seção 6.2.
2. **Vínculo com autenticação do usuário: por tempo ou por operação?** Por
   operação é biometria a cada chamada de provedor, dezenas por sessão. Por
   tempo é uma autenticação que autoriza uma janela. Seção 6.2.
3. **Quantos módulos por entrega?** A calculadora foi em três PRs (`:core:calc`,
   `:core:data`, `:app`). Aqui são ~6.700 linhas de origem e quatro módulos.
   Proposta: quatro entregas, na ordem `:core:protocolo` → `:core:provedores` →
   `:core:sessao` → `:app`, cada uma com seus testes e portão verde.

### Abertas, dependem de medição no aparelho

1. **Teto real do serviço em primeiro plano `dataSync`.** A página oficial de
   tipos de serviço não declara limite de tempo para `dataSync` — declara para
   `shortService` (3 min) e `mediaProcessing` (~6 h). Se existe limite adicional
   imposto pelo sistema, ele tem de ser medido em aparelho antes de a v1 sair,
   porque governa o valor máximo aceitável de `max_runtime_minutes`.
2. **Timeout por chamada na Perplexity com `store: false`.** Com `store: false`
   não há modo assíncrono, e a medição de 17/09/2026 registrou o caminho
   síncrono passando de 40 s sem responder. O teto por chamada precisa ser
   calibrado com medição, não arbitrado.

### Resolvidas nesta especificação

- **Gemini por Vertex ou pela API geral?** Resolvida pelo operador em
  21/09/2026: API geral. Vertex exige cadastro no GCP e não serve a produto de
  consumo.
- **SDK oficial ou REST?** Resolvida por medição na seção 5.3: REST para os
  seis, porque nenhum provedor publica SDK Android.
- **Retenção no provedor?** Resolvida na seção 6.4: `store: false` explícito.
- **Onde fica a chave?** Resolvida pelo operador em 21/09/2026: no aparelho,
  Android Keystore direto.

### Fora deste repositório, aberta, com prazo

- **O Maestro AI web chama `POST /v1/sonar`**, medido em
  `admin-motor/src/handlers/routes/maestro-ai/sessions.ts` em 21/09/2026 — o
  endpoint que a Perplexity desliga em **27/09/2026**. Não é escopo desta
  especificação nem deste repositório, mas é achado com data e está registrado
  no rastreador do `admin-app`. Aqui fica só a referência cruzada.

---

Registro por Claude Code (Claude Opus 5), sob direção do operador.
