# Especificação — Maestro AI Android v1

Ordem do operador em 21/09/2026: *"Vamos iniciar o desenvolvimento do Maestro AI
(Android) nas mesmas bases e diretrizes da Calculadora Financeira (Android)."*
Este documento registra **o que foi decidido e por quê**, para que nenhuma
escolha precise ser redescoberta ou relitigada.

Toda verificação de documentação de provedor citada aqui foi feita em
**21/09/2026**, na documentação oficial do próprio provedor, e não de memória.
A única exceção é a linha do `grok` na seção 5.1, reconfirmada na documentação
oficial da xAI em **22/09/2026**; as dos outros cinco provedores continuam com a
data de 21/09.

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
| Auditoria de links e defesa de SSRF (1629–1935) | 307 | porta **do Rust atual**, com o modelo de ameaça invertido — seção 5.4 e o parágrafo abaixo |
| Auditoria do candidato a release final (1936–1984) | 49 | porta **do Rust atual**, em cinco estágios — parágrafo abaixo |
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

**Só que a fonte canônica desta unidade não é o web, e a frase da seção 1 não
vale aqui.** O `content-lock.ts` se declara, na primeira linha, *"byte-exact
port of maestro-app (canonical) src-tauri/src/editorial_content_lock.rs"* — o
web é ele próprio um porte, e aponta o Rust como canônico. O arquivo Rust tem
**959 linhas: 607 de implementação e 352 de testes**, contra 526 do porte web,
e este declara um desvio: chaveia igualdade de bloco pelo texto normalizado em
vez do SHA-256.

Portar o TypeScript seria portar um porte, herdando o desvio e inventando do
zero uma suíte que já existe. **Decisão do operador em 21/09/2026: o Kotlin
porta do Rust**, com o TypeScript como conferência cruzada, e a suíte canônica
vem junto. Isso não contradiz a regra de que o produto vem do web — contradiz
apenas a suposição de que *toda* unidade vem de lá, que este caso desmente.

A mesma pergunta deve ser feita a cada unidade antes de portá-la: **de onde ela
é canônica?** Uma unidade que o web tenha escrito primeiro vem do web; esta não
é uma delas.

**E o contrato do relatório muda aqui — decisão do operador de 22/09/2026.** No
canônico, um bloco editado e movido é indistinguível de um acréscimo: os dois
textos não dizem se ele moveu, e `changed_blocks` só carrega `block_id` da
custódia recebida. Três heurísticas de pareamento foram escritas e derrubadas
em revisão, uma por rodada. O Android passa a exigir, quando o texto revisado
tem bloco que não é cópia intacta de um recebido, a seção
`revised_block_origins`: uma entrada por bloco revisado, na ordem do texto, com
o começo do bloco como conferência e o `block_id` de origem ou `addition`. E o
relatório passa a ser lido como JSON estrito, o que o prompt canônico já pedia.

O que isso **não** fecha fica escrito: a declaração pode mentir. O ganho é que
o movimento silencioso deixa de existir — escondê-lo passa a exigir uma
afirmação falsa, atribuível e justificada. Registro completo na
[Discussion #41](https://github.com/LCV-Ideas-Software/maestro-android/discussions/41).
A mesma lacuna existe no canônico e no `admin-app` (MAESTRO-30, ADMIAPP-29); a
adoção lá é decisão de cada repositório.

**A auditoria do candidato final também vem do Rust — e do Rust atual, que
cresceu. Decisão do operador de 24/09/2026 (MAEANDR-18).** O próprio
`sessions.ts` se declara porte do canônico nesse trecho (*"canonical release
link audit (port of link_audit.rs)"*), mas portou uma versão de três estágios.
Medido no `maestro-app` em `68528f9`, o Rust tem cinco:

1. integridade bibliográfica (marcador de evidência pendente ou lacuna);
2. citações ABNT (`abnt_citation.rs`, 1.769 linhas), que **sem manifesto de
   citações recusa toda citação detectada** (`structured_manifest_missing`);
3. no máximo 30 ocorrências de link;
4. o motor de integridade de links (`link_integrity.rs`, 1.069 linhas), que
   coleta cada link pelo motor de evidências (`web_evidence.rs`, 3.660 linhas);
5. **nenhum link sai sem revisão explícita** contra a URL e o hash do conteúdo
   atuais; resposta HTTP bem-sucedida é só `verified_but_weak`.

Perguntado entre os três estágios do web e os cinco do Rust atual, o operador
escolheu os cinco. O que a escolha pede, e como fica:

- **Manifesto de citações:** entra como anexo JSON da sessão
  (`citation_manifest.v1`), como no desktop; o `:core:sessao` guarda os
  anexos e o `:app` oferece a entrada.
- **Revisão de cada link:** tela no `:app`, registros no Room do
  `:core:sessao`.
- **Janela de navegador (WebView2 no desktop):** não é portada. O link abre no
  navegador do sistema, que já é isolado do aplicativo, e o operador importa o
  arquivo salvo pelo seletor oficial de arquivos do Android.
- **Busca de evidências:** só os dois conectores embutidos do canônico,
  Crossref e OpenAlex, que não usam chave. Os conectores configuráveis ficam
  como pendência (seção 11).
- **Onde mora cada parte:** regras puras no `:core:protocolo`; rede (parser de
  URL, DNS, coleta, busca) no `:core:provedores`.

Onde o porte é **mais estrito que o canônico**, de propósito. Salvo os dois
últimos, que são decisões do operador, os pontos corrigem defeitos que a
revisão do Codex achou na PR #57 e depois do merge dela, e que também estão
no Rust em `68528f9`:

- **só se aceita link cuja verificação mecânica passou.** O Rust só conferia o
  código HTTP: página de captcha, de login ou de paywall, ou evidência
  bloqueada, servida com 200, podia ser aceita como suporte. Só passa evidência
  pronta e sem interação pendente. Na fila, em coleta, vencida ou à espera do
  operador, ou com pedido de consentimento ou de confirmação de download, ela
  vai para quarentena e conta como bloqueada, qualquer que seja o código HTTP
  guardado nela de uma coleta anterior.
  Cada linha guarda a classificação mecânica à parte da que a revisão escreve
  (`mechanical_classification`), e um aceite anterior só é preservado enquanto
  a verificação nova ainda passar;
- **aceitar link HTTP(S) exige o hash do conteúdo da evidência.** O Rust
  conferia hash ausente com hash ausente, e o aceite, preso a conteúdo nenhum,
  sobrevivia a qualquer mudança do destino;
- **URL que o saneamento alteraria fica bloqueada.** O Rust guarda a URL
  normalizada já saneada — cortada em 1.000 pontos de código, com padrão de
  segredo trocado por `<redacted>` — e coleta essa URL alterada: a revisão
  aprovaria evidência de outro destino;
- **sem manifesto, nota de rodapé, `<cite>`, `<blockquote>`, `<q>` e `apud`,
  `ibid.`, `op. cit.` bloqueiam** mesmo num texto sem citação autor-data; no
  Rust esses sinais só eram conferidos com manifesto;
- **acima de 500 citações, aspas, sinais de um tipo ou referências, o texto é
  recusado.** O Rust para de ler no limite e ignora o excedente em silêncio;
- **valor que dobra para vazio nunca está presente no texto.** Só pontuação,
  ou só letras que o dobramento ASCII descarta, dobram para vazio, e o
  `contains("")` do Rust é verdadeiro para qualquer texto: citação,
  referência, marcador de nota ou chave de autor ausentes passavam por
  presentes. Dois valores que dobram os dois para vazio, como dois nomes
  gregos, são comparados sem o dobramento, e a chave de autor que dobra para
  vazio casa com a própria referência do mesmo jeito;
- **o HTML cru do texto final é mascarado antes da busca de aspas.** O texto
  final é Markdown, e quem reconhece o HTML é a `commonmark-java`, pela seção
  6.6 da especificação CommonMark: tag, comentário, instrução de
  processamento, declaração e CDATA, cada um até o fechamento que a
  especificação define (decisão do operador de 24/09/2026, no lugar de um
  reconhecimento escrito à mão). O bloco HTML fica desligado, para que a
  prosa dentro de um `<div>` continue conferida; num segundo passe, o bloco
  de comentário, instrução, declaração ou CDATA, que pode atravessar uma
  linha em branco, é mascarado até o seu fechamento. Marcação não vira citação
  nem pareia com as aspas da prosa em volta. O Rust
  pulava a aspa reta depois de qualquer `<` sem `>` adiante, ou logo depois de
  um `=`: prosa como `2 < 3 e "..."` escondia uma citação direta sem fonte,
  e a aspa que fecha um atributo pareava com a que abre o seguinte;
- **o site-local IPv6 (`fec0::/10`) é recusado, e o IPv4 dentro do NAT64
  (`64:ff9b::/96`, e o de uso local `64:ff9b:1::/48`, lido em cada leiaute
  do RFC 6052 que os bytes permitem) e do 6to4 (`2002::/16`) é julgado como
  IPv4.** O Rust deixava todos chegarem à rede local do usuário. O prefixo
  NAT64 não é recusado inteiro porque, numa rede com DNS64, todo site só
  IPv4 resolve para ele;
- **os auxiliares do turno que não revisou o texto recebem o contexto de
  citações da sessão.** No Rust eles auditam sem manifesto, e na retomada da
  sessão (`restore_circular_resume_progress`) um revisor `READY` sobre texto
  com manifesto válido deixava de contar como aprovação estável;
- **cada citação do corpo consome uma entrada própria do manifesto**
  (decisão do operador de 24/09/2026). Citar o mesmo autor, ano e localizador
  duas vezes, para duas afirmações, pede duas entradas. No Rust uma entrada
  cobria todas as ocorrências iguais, e a segunda afirmação saía sem
  verificação própria. Texto com forma de citação dentro da seção de
  referências, como um título, não consome entrada: só precisa estar
  representado, como no Rust. A seção termina no próximo cabeçalho, e um
  apêndice depois dela é corpo;
- **manifesto com chave JSON repetida é recusado** (decisão do operador de
  24/09/2026). O Rust o lê primeiro como `Value` e fica com o último valor:
  dois leitores do mesmo arquivo veriam manifestos diferentes.

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

- ~~**`java-kotlin` continua fora do CodeQL.** Não por falta de Kotlin, mas porque
  o CodeQL não suporta o Kotlin 2.4.20 da frota — medido em 18/09/2026 e
  rastreado pela CALANDR-14.~~ **Superado em 24/09/2026 (MAEANDR-16):** o CodeQL
  2.27.1 suporta o Kotlin 2.4.20, e a configuração padrão analisa `java-kotlin`
  com *autobuild*. O `quality/code-quality-probe.js` permanece como
  *placeholder* e é a única fonte JavaScript do repositório, agora por causa do
  Code Quality: a análise por regras dele cobre C#, Go, Java, JavaScript,
  Python, Ruby e TypeScript, e o modo `none` com que ele compila não extrai
  Kotlin. Por decisão do operador em 24/09/2026, o placeholder fica até o Code
  Quality cobrir Kotlin (MAEANDR-20).
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
                  auditoria de release (cinco estágios: ABNT e integridade
                  de links), montagem de prompts, custo
:core:provedores  Kotlin puro — os seis provedores sobre OkHttp, retry
                  e uso; lê a chave por uma interface, não pelo Keystore;
                  parser de URL, DNS, coleta e busca da auditoria de links
:core:seguranca   Android library — a implementação da chave sobre o Keystore
:core:sessao      Android library — Room (inclusive os registros de links e
                  os anexos da sessão), orquestração da deliberação, retomada
:app              Compose (inclusive a revisão de links e o manifesto de
                  citações), WorkManager, injeção que amarra os módulos
```

`:core:protocolo` **não depende de Android**, pela mesma razão que fez
`:core:calc` valer a pena na calculadora: ele concentra a parte que decide se um
turno é válido — 621 linhas de leitura de relatório mais as 526 do
*content-lock* —, e essa parte tem de poder ser executada na JVM, em
milissegundos, a cada mudança. É onde os testes rendem mais por linha.

**`:core:provedores` também é Kotlin puro, e isso exige um cuidado.** O Android
Keystore é API de plataforma; um módulo que o chamasse deixaria de rodar na JVM,
e os seis clientes HTTP cairiam junto — precisariam de Robolectric ou de
execução instrumentada para um teste que só quer conferir o corpo de uma
requisição. Por isso os clientes **não** conhecem o Keystore: eles pedem o
segredo a uma interface (`FonteDeChave`, um método por provedor), e quem a
implementa sobre o Keystore é `:core:seguranca`, que `:app` injeta. Em teste,
a mesma interface é satisfeita por um valor em memória.

A fronteira não é enfeite: é o que mantém na JVM o módulo que fala com dinheiro
alheio e com seis contratos externos.

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

#### O teto de seis horas, que é documentado e governa `max_runtime_minutes`

Para quem mira o Android 15 ou superior — e a v1 mira o 37 —, o `dataSync` tem
teto: *"The system permits an app's `dataSync` services to run for a total of 6
hours in a 24-hour period, after which the system calls the running service's
`Service.onTimeout(int, int)` method."* Ao ser chamado, *"the service has a few
seconds to call `Service.stopSelf()`"*, e se não chamar o sistema lança
`RemoteServiceException` com a mensagem *"A foreground service of type dataSync
did not stop within its timeout"*. O relógio **não** é por sessão: é agregado em
24 horas, e só *"if the user brings the app to the foreground, the timer resets
and the app has 6 hours available."*

Três consequências, todas vinculantes:

1. **`max_runtime_minutes` tem teto de produto abaixo de seis horas.** Um valor
   que o usuário configure acima disso não é atendível e não deve ser aceito
   pela tela.
2. **O ponto de retomada é gravado a cada turno, e não no fim.** Ver a
   subseção seguinte: o `Service.onTimeout()` **não chega ao nosso código**, e
   por isso a proteção não pode depender de reagir a ele.
3. **O orçamento é agregado.** Duas sessões longas no mesmo dia dividem as seis
   horas. A tela tem de dizer isso quando o saldo estiver baixo, em vez de
   deixar a segunda sessão morrer sem explicação.

#### Quem recebe o `onTimeout()` não é o nosso *worker*

Uma versão anterior deste documento prescrevia que, ao chegar o teto, *"o worker
grava o ponto da deliberação, para o serviço e deixa a sessão em estado
retomável"*. **O *worker* não consegue fazer isso.** Com o WorkManager, o
serviço em primeiro plano é a classe interna
`androidx.work.impl.foreground.SystemForegroundService`: é a ela que o sistema
entrega `Service.onTimeout()`, e é ela que chama `stopSelf()`. Um
`CoroutineWorker` não implementa `onTimeout()` nem para o serviço. A prescrição
era uma sequência impossível.

O que resolve não é um retorno de chamada melhor, é **não precisar de nenhum**:

1. **Checkpoint por turno, obrigatório.** A deliberação grava o ponto retomável
   ao fim de **cada turno de agente**, dentro da mesma transação que grava o
   artefato e o evento do jornal. Assim o pior caso — seja teto de seis horas,
   cota do Android 16, morte de processo ou o usuário forçando a parada — perde
   **no máximo o turno em voo**, nunca a sessão. Gravar só no fim seria confiar
   num aviso que pode não vir.
2. **`onStopped()` é aproveitado, mas como rótulo, não como salvaguarda.** O
   WorkManager *"invokes `ListenableWorker.onStopped()` as soon as your Worker
   has been stopped"*, e o motivo é legível pelo próprio *worker* e pelo
   `WorkInfo`, por `getStopReason()`. `STOP_REASON_TIMEOUT` e
   `STOP_REASON_QUOTA` são exatamente os dois limites desta seção. Usar isso
   para dizer ao usuário **por que** a sessão pausou é melhora real de produto;
   usar como o lugar onde o estado é salvo seria repetir o erro, porque o
   retorno de chamada é *best-effort* e o processo pode nem chegar lá.
3. **A saída, se a medição mostrar que não basta**, é serviço em primeiro plano
   **do próprio aplicativo**, em que `onTimeout()` chega ao nosso código e o
   `stopSelf()` é nosso. Isso troca o agendamento, não o resto do desenho, e
   está registrado como pendência na seção 11 junto com a da cota.

A versão do WorkManager que o catálogo fixar tem de expor `getStopReason()`;
conferir na entrega, e não presumir pela versão mais recente do dia.

#### A cota de *jobs* do Android 16, que atinge a v1 sim

A documentação do WorkManager é explícita: a partir do Android 16, *worker*
longo que usa serviço em primeiro plano **pode esgotar a cota de jobs do
aplicativo**, e as saídas oficiais são subir o serviço em primeiro plano
diretamente, sem WorkManager, ou usar *user-initiated data transfer jobs*, que
são isentos de cota.

Uma versão anterior deste documento dizia que isso não atingia a v1 "porque a
sessão é sempre iniciada por toque do usuário com o aplicativo aberto". **Isso
estava errado**, e o erro é de leitura: a isenção que a documentação descreve é
a dos *user-initiated data transfer jobs*, um mecanismo específico — não o fato
de o usuário ter tocado num botão. Fica registrado porque a frase errada já
esteve publicada.

A v1 mantém o WorkManager, pelo agendamento e pela persistência que ele dá de
graça, e trata o esgotamento de cota como **mais uma forma de interrupção**,
idêntica em efeito à morte de processo: a sessão fica retomável e a abertura do
aplicativo reconcilia (seção 4.3). Se a medição em aparelho mostrar que a cota
interrompe sessões normais, a troca é subir o serviço em primeiro plano
diretamente — decisão que fica registrada como pendência na seção 11, com a
saída já nomeada.

Uma terceira restrição documentada não atinge a v1: aplicativo que mira o
Android 15 ou superior não pode subir serviço `dataSync` a partir de um receptor
de `BOOT_COMPLETED`. A v1 não tem receptor de `BOOT_COMPLETED`.

#### A notificação não é superfície confiável, e o controle não pode morar só nela

A notificação em primeiro plano é obrigação técnica e mostra a rodada atual, o
agente da vez, o custo acumulado e uma ação de cancelar. Mas ela **pode não ser
vista**: no Android 13 e acima, negada a permissão `POST_NOTIFICATIONS`, *"they
still see notices related to foreground services in the Task Manager but don't
see them in the notification drawer"*. O serviço roda; o aviso some da gaveta.

Numa configuração que o sistema permite, portanto, o custo ao vivo e o botão de
cancelar desapareceriam enquanto a sessão segue gastando a chave do usuário.
Isso é inaceitável para o único produto da frota que gasta dinheiro do usuário
sozinho. Duas obrigações decorrem:

1. **O aplicativo pede `POST_NOTIFICATIONS` antes da primeira sessão**, com a
   razão dita em texto — não no arranque, não sem explicação.
2. **A tela de sessão é a superfície canônica de status e de cancelamento**, com
   custo acumulado ao vivo e cancelar, e a notificação é conveniência que
   espelha. Negada a permissão, nada de essencial se perde; ganha-se um aviso a
   menos.

### 4.2 Room no lugar do D1

O esquema do D1 (166 linhas de `ensureSchema`) vira entidades do Room: sessão,
artefato e configurações. O que no web é `GET /sessions/{id}` em *polling* vira
`Flow` observado pela interface; o *worker* escreve, a tela lê, e não há
serialização de eventos indo e voltando por HTTP.

**O backup do Android tem de ser recortado, e por omissão ele não é.**
`android:allowBackup` vale `true` quando não declarado, e aplicativo que mira a
API 23 ou superior *"automatically participate in Auto Backup"*. O que o Auto
Backup leva por padrão inclui, citado, *"files in the directory returned by
`getDatabasePath(String)`"* e os arquivos do armazenamento interno — isto é,
exatamente o banco do Room com o texto das sessões e o DataStore com o segredo
cifrado. Sem recorte, o conteúdo do usuário sobe para o serviço de backup
configurado no aparelho, o que contradiz a promessa da seção 6.

**O segredo cifrado fica em `noBackupFilesDir`**, que o Android exclui sempre
do backup e da transferência entre aparelhos — "mesmo se você tentar
incluí-los", diz a documentação do Auto Backup —, com o temporário que o
DataStore grava ao lado do arquivo junto; a exclusão não depende de regra de
caminho. **O banco do Room**, quando existir, é excluído por
`android:dataExtractionRules` (API 31+), nos dois domínios que a regra separa —
`cloud-backup` e `device-transfer`. A exclusão é do conteúdo, não do
aplicativo: preferência de interface pode ser restaurada sem problema.

**E o cifrado restaurado não decifra.** A chave do Keystore não é exportável e
não viaja com o backup, então texto cifrado que chegasse a outro aparelho seria
lixo indecifrável. Excluí-lo do backup já evita o caso.

Para o resto, **"falhou a decifra" não é um caso só**, e tratar como se fosse
custaria ao usuário exatamente o que se quer evitar: digitar de novo uma chave
de API que está intacta. São três causas, com três respostas:

| Causa | O que é | Resposta |
| --- | --- | --- |
| `UserNotAuthenticatedException` | a janela de autenticação expirou — *"the key's validity timed out"*. **A chave está intacta.** | `pausada_aguardando_autenticacao` (seção 6.2): pedir **autenticação**, nunca a chave de API |
| `KeyPermanentlyInvalidatedException` | a chave do Keystore foi invalidada em definitivo, tipicamente por mudança de biometria ou da trava de tela | o segredo é irrecuperável: dizer isso em texto claro e **pedir a chave de API de novo** |
| Decifra falha sem exceção de autenticação, ou o alias não existe | cifrado órfão — o caso do backup restaurado | tratar como **"não há chave configurada"** e pedir a chave de novo |

Só a terceira linha era o que este documento dizia, como regra geral; as duas
primeiras entraram depois de a revisão apontar que a regra geral engolia a
primeira e mandava o usuário redigitar chave boa.

**Remover a trava de tela apaga a chave**, medido num emulador Android 17 em
23/09/2026: o alias some do Keystore, em vez de a chave passar a lançar
`KeyPermanentlyInvalidatedException`. O caso cai na terceira linha — cifrado
órfão, "não há chave configurada" —, e a tela precisa dizer por quê só se a
trava tiver sido removida, o que ela pode conferir no `KeyguardManager`.

A separação atravessa a fronteira da seção 4: `FonteDeChave` devolve uma
`LeituraDaChave` — `Presente`, `Ausente`, `ExigeAutenticacao`,
`Irrecuperavel` ou `Indisponivel` —, e o `:core:provedores` transforma cada uma
das quatro últimas num resultado próprio (`SemChave`, `ExigeAutenticacao`,
`SegredoIrrecuperavel`, `ChaveIndisponivel`), sem fazer requisição.
`Indisponivel` é a falha passageira do aparelho — Keystore ocupado, disco que
não lê —, sem sinal de que a chave se perdeu: pedir a chave de novo, nesse
caso, seria mandar redigitar uma chave intacta. Uma interface que devolvesse só
"a chave ou nada" apagaria a distinção antes de ela chegar à sessão. Pela
mesma razão, disco que não lê e Keystore que falha de forma indeterminada não
respondem "configurada" nem "não configurada": o cofre devolve que não sabe
agora, e a tela mostra um terceiro estado — "não foi possível verificar
agora" —, em vez de afirmar um dos dois (decisão do operador de 23/09/2026).

A segunda causa tem mitigação própria e a v1 a usa:
`setInvalidatedByBiometricEnrollment(false)` mantém a chave válida quando uma
biometria nova é cadastrada. Cadastrar um dedo novo não é motivo para alguém
perder as seis chaves.

Um aplicativo que quebra depois de trocar de aparelho perdeu o usuário no
primeiro minuto; um que pede a chave de volta toda vez que o relógio virou é
pior, porque parece estar funcionando.

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

### 5.1 O quadro, reconferido em 23/09/2026

| Agente | Modelo | Transporte | Controle de raciocínio | Valor fixado |
| --- | --- | --- | --- | --- |
| `claude` | `claude-fable-5-1` | `POST https://api.anthropic.com/v1/messages` | `thinking: {type: "adaptive"}` + `output_config.effort`: `low`…`max` | `max` |
| `codex` | `gpt-6-astra` | `POST https://api.openai.com/v1/responses` | `reasoning.effort`: `minimal`…`max`, sem padrão; `none` devolve 400 | `max` |
| `gemini` | `gemini-3.1-pro-preview` | `POST https://generativelanguage.googleapis.com/v1beta/interactions` | `generation_config.thinking_level`: `low`, `medium`, `high` (padrão `high`) | `high` |
| `deepseek` | `deepseek-v4-pro` | `POST https://api.deepseek.com/chat/completions` | `thinking: {type: "enabled", reasoning_effort}`: `none`, `low`, `high`, `max` | `max` |
| `grok` | `grok-4.7` | `POST https://api.x.ai/v1/responses` | `reasoning.effort`: `low`, `medium`, `high`, `xhigh` (padrão `high`) | `xhigh` |
| `perplexity` | `perplexity/sonar` | `POST https://api.perplexity.ai/v1/agent` | `preset`: `fast`, `low`, `medium`, `high`, `xhigh` | `xhigh` |

**Valor fixado: o máximo de cada provedor, decisão do operador de 23/09/2026.**
A versão anterior do quadro listava as faixas sem escolher o valor, e no
`gpt-6-astra` a escolha é obrigatória: a documentação diz que ele não tem padrão.
Com o raciocínio no máximo, o teto de saída de cada chamada é **64 mil tokens**,
raciocínio incluído (decisão do operador de 23/09/2026): é o ponto de partida
que a Anthropic documenta para `max`, e cabe no teto de saída de cada modelo —
Claude Fable 5.1 e GPT-6 Astra 128 mil, Gemini 3.1 Pro 65.536, DeepSeek 384 mil,
Grok 4.7 128 mil por padrão. A Perplexity não publica o teto do
`perplexity/sonar`, que tem 128 mil de contexto; ver a seção 11.

**Três linhas mudaram na reconferência de 23/09/2026.** O endereço do Gemini é
`/v1beta/interactions`, e não `/v1beta2/`, como o quadro dizia: é o endereço do
exemplo REST oficial, e a Interactions API está disponível para uso geral desde
junho de 2026. A DeepSeek passou a expor o controle de raciocínio, com o esforço
**dentro** de `thinking`, como a referência da API documenta. E o modelo da
Perplexity mudou, pelo motivo do parágrafo sobre a Perplexity, logo abaixo.

Três escolhas são do operador — `claude-fable-5-1`, `gemini-3.1-pro-preview` e o
uso da API geral do Gemini. As outras decorrem da regra: `gpt-6-astra` é o
modelo de raciocínio corrente da OpenAI, `grok-4.7` é o Grok corrente, e
`deepseek-v4-pro` é o único não-Flash que a DeepSeek oferece.

**Grok, reconfirmado em 22/09/2026, quando o operador anunciou o lançamento.** A
página oficial de modelos lista `grok-4.7` e o descreve como *"the most capable
model we've built"*, com 500 mil de contexto; as notas de versão registram
*"now available on the xAI API as `grok-4.7`"* e o esforço de raciocínio em
*"low, medium, high (default), and xhigh"* — valores que esta tabela ainda não
trazia. Nenhum modelo de texto do Grok aparece como descontinuado. As notas de
versão agrupam as entradas por mês, sem dia, e `grok-4.7` e `grok-4.6` aparecem
ambos em setembro: pela documentação não se prova se, em 21/09, o `grok-4.7` já
estava publicado quando esta tabela o registrou.

**Perplexity: `perplexity/sonar`, com o preset `xhigh`.** Uma versão anterior
deste quadro fixava `sonar-reasoning-pro`. Na Agent API, a lista oficial de
modelos traz `perplexity/sonar` como o **único** modelo próprio da Perplexity;
os outros são de terceiros servidos por ela (`anthropic/…`, `openai/…`), e usar
um deles aqui seria outro provedor disfarçado de Perplexity. A capacidade vem
do preset `xhigh`, o maior, que aceita o modelo explícito. É a mesma decisão do
operador de 22/09/2026 para o Maestro AI web (ADMIAPP-33), confirmada lá com
chamada autenticada. O pin `perplexity/kimi-k3` que existe no cross-review é
decisão daquele produto e **não** se transporta para cá.

### 5.2 Os três recursos de API que mudaram e precisam entrar

Não são detalhes de implementação: cada um invalida o jeito antigo de chamar.

1. **Anthropic — `budget_tokens` morreu.** Nos modelos correntes o campo é
   rejeitado com HTTP 400. O caminho é `thinking: {type: "adaptive"}` com
   `output_config.effort`.
2. **Google — a Interactions API substituiu o `generateContent`.** É *"the new
   standard for building with Gemini, recommended for all new projects"*; o
   `generateContent` segue suportado. Muda o endpoint (`/v1beta/interactions`),
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

Decisão: **OkHttp contra os contratos REST documentados, para os seis.** Os
contratos REST são a interface oficial e documentada de todos eles, o que mantém
a diretriz de usar solução oficial; e um transporte só significa uma taxonomia de
erro e uma contabilidade de custo testadas uma vez em vez de seis. Misturar um
SDK que não afirma suportar Android com cinco clientes REST seria carregar peso,
risco de R8 e uma segunda linha de inventário em
[`THIRDPARTY.md`](../THIRDPARTY.md) para não ganhar nada.

Uma versão anterior desta seção dizia "Retrofit/OkHttp". Cada provedor é um
`POST` num endpoint só, com corpo JSON montado pelo Jackson, e o Retrofit só
embrulharia o mesmo OkHttp; o operador decidiu em 23/09/2026 pelo OkHttp sozinho,
com o módulo oficial `okhttp-coroutines` ligando o cancelamento da corrotina ao
da chamada. A mesma versão prometia "uma abstração de *streaming*": nem o web nem
o desktop usam *streaming*, e a v1 também não — cada turno é uma chamada
completa, com o prazo da seção 4.1.

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
o aplicativo varrendo o roteador de quem o instalou. ~~A unidade porta inteira —
`isPrivateIpv4`, `embeddedIpv4FromIpv6`, `isBlockedAuditHost` e a resolução
prévia de host —~~ A unidade porta do Rust atual (seção 2.2): as faixas
bloqueadas e a recusa de URL não pública de `link_audit.rs`, e o resolvedor que
recusa endereço privado na própria conexão, de `web_evidence.rs`. O motivo de
existir passa a ser **proteger a rede do usuário**, não a nossa. O comentário
no código diz isso (`RedePublica`); herdar a defesa sem herdar a razão é como
ela apodrece.

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

Atenção: o que o Keystore protege é a **chave AES que cifra o segredo**, e não a
chave de API em si. A chave de API existe em claro, em memória, no instante de
montar a requisição, e é isso que permite a distinção correta da subseção
seguinte.

#### A frase honesta, e a que não se usa

**Não se diz "sua chave de API nunca sai do aparelho".** É falso: toda chamada a
um provedor manda a chave dele, como credencial de autenticação, para o próprio
provedor. Não haveria como funcionar de outro jeito.

O que é verdade, e é o que a tela de configurações dirá com estas palavras:

- a chave é **guardada apenas neste aparelho**, cifrada por chave do Keystore
  que não é exportável;
- ela é **enviada, por TLS, apenas ao provedor a que pertence**, e só quando o
  usuário roda uma sessão com aquele provedor ativo;
- **nenhum servidor da LCV Ideas & Software** a recebe, vê ou guarda — não há
  servidor nosso no caminho;
- ela **nunca é exibida de volta** depois de gravada: a tela mostra
  "configurada" ou "não configurada", nunca o valor.

A diferença entre as duas formulações é a diferença entre uma promessa que se
cumpre e uma que o primeiro usuário atento desmente. Este documento usou a
formulação errada antes desta revisão, e ela chegou a ser publicada.

### 6.2 Duas decisões do operador, tomadas em 21/09/2026

Ambas têm custo real e nenhuma tinha resposta na documentação — eram escolha de
produto, e o operador as fez. Ficam aqui escritas para não serem relitigadas.

**1. StrongBox: sim** (`setIsStrongBoxBacked(true)`), disponível desde a API 28
e portanto em todo aparelho que o `minSdk` 34 alcança. A documentação é
explícita sobre o preço, e o preço está aceito: *"Appropriate for applications
requiring the highest level of security... However, it is slower, more
resource-constrained, and supports fewer concurrent operations."*

Ser mais lento não incomoda aqui. A chave do Keystore é usada para decifrar o
segredo **uma vez por chamada de provedor**, e cada chamada de provedor leva
segundos ou dezenas de segundos de rede e raciocínio; a operação de cifra não é
o que se sente. "Menos operações simultâneas" é o ponto que exige cuidado, e o
desenho já o resolve: o segredo é decifrado no momento de montar a requisição,
não mantido em uso.

**Nem todo aparelho tem o hardware**, e isso não é hipótese remota — é a maior
parte dos aparelhos baratos. A criação da chave com StrongBox falha nesses, e a
falha é tratada, não engolida: o aplicativo recria a chave sem StrongBox e
**registra qual dos dois caminhos está em uso**, para que a tela de
configurações possa dizer a verdade sobre este aparelho em vez de uma promessa
genérica. Degradar em silêncio seria prometer a todos o que só alguns têm.

**2. Vínculo com autenticação do usuário: por tempo, até a entrega do texto
final** (`setUserAuthenticationParameters()`). O usuário se autentica uma vez, e
a janela cobre a sessão de deliberação até o texto final sair. O outro modo —
autorizar cada operação — significaria biometria a cada chamada de provedor,
dezenas por sessão, o que é atrito sem ganho proporcional num aplicativo que o
próprio usuário deixou rodando.

**A janela é fixa, e não podia ser de outro jeito.** Uma versão anterior deste
documento oferecia, como alternativa a decidir por medição, "janela dimensionada
pelo `max_runtime_minutes` daquela sessão". **Isso é impossível**, e não por
dificuldade: a política de autorização é gravada na chave quando ela nasce —
*"Once a key is generated or imported, its authorizations can't be changed.
Authorizations are then enforced by the Android Keystore whenever the key is
used."* Redimensionar por sessão exigiria gerar chave nova a cada sessão, o que
tornaria indecifrável o segredo já cifrado com a anterior. Fica registrado
porque a frase errada já esteve publicada.

O desenho que atende à decisão do operador é, então:

1. **Uma janela só, escolhida uma vez**, dimensionada pelo teto máximo de sessão
   que o produto aceita — que a seção 4.1 já limita, porque o `dataSync` não
   passa de seis horas num período de 24.
2. **Se a janela expirar com a sessão viva, a sessão pausa e pede
   reautenticação**, em vez de falhar decifra atrás de decifra. Isso não é
   detalhe: `BiometricPrompt` exige tela visível, então **o *worker* em segundo
   plano não consegue reautenticar sozinho**. A sessão vai para um estado
   `pausada_aguardando_autenticacao`, a notificação e a tela dizem por quê, e o
   usuário retoma com um toque. Como trazer o aplicativo para primeiro plano
   também reinicia o relógio de seis horas do `dataSync`, o mesmo gesto resolve
   os dois limites.
3. **Trocar o teto de sessão troca a chave.** Se o produto um dia aumentar o
   teto, a chave precisa ser regerada, e regerar exige **decifrar com a antiga e
   recifrar com a nova, na mesma operação autenticada** — nunca apagar a antiga
   antes. Está escrito aqui para que não seja descoberto com o segredo do
   usuário na mão.

O que está decidido e não se revisita é o modo: **por tempo, não por operação.**

A v1 assume `minSdk` 34, herdando a decisão do operador de 19/09/2026 na
calculadora. Isso torna as duas APIs acima universalmente disponíveis e dispensa
caminho por nível de API — o que sobra é o hardware de StrongBox, que é questão
de aparelho, não de versão do sistema.

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
- **xAI Responses:** expõe `store`, sem padrão documentado. Esta seção dizia,
  até a reconferência de 23/09/2026, que a xAI não tinha o campo.

E os outros **dois não têm o campo**: o `POST /v1/messages` da Anthropic e o
`/chat/completions` da DeepSeek não o declaram. Mandar campo que a API não
conhece é pedir recusa, não privacidade.

Um produto que promete guardar o texto do usuário só onde ele escolheu não pode
deixá-lo retido por padrão. **`store: false` é explícito em todo pedido dos
quatro provedores que expõem o campo, e ausente nos outros dois.** A
serialização é por provedor, não uma chave costurada em todos — e a seção 8
testa exatamente isso, inclusive que os dois sem o campo não o recebem.

**O que `store: false` não faz, e a tela não pode prometer.** Ele impede o
objeto guardado para consulta posterior, e não a retenção do provedor para
auditoria de abuso. A xAI é explícita: *"By default, all API requests and
responses are stored on our servers (encrypted at rest) for 30 days for auditing
purposes in the event of suspected abuse or misuse"*, e só a retenção zero
(ZDR), ligada pelo administrador da conta do usuário na xAI, evita isso. A frase
honesta para a tela é que o aplicativo pede a cada provedor para não guardar a
conversa, e que cada provedor ainda aplica a própria política de retenção.

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

No Android tudo isso é `BigDecimal`. O teto de custo é a única barreira entre uma
sessão mal configurada e a fatura do usuário, e não pode depender de
arredondamento implícito.

### 7.1 A regra do teto, declarada — não "a declarar"

Uma versão anterior desta seção prometia "escala e arredondamento declarados no
ponto de comparação" e não os declarava, e o plano de testes mandava testar "o
comportamento no limite exato" sem que nada dissesse qual é. Duas implementações
poderiam obedecer ao documento com `<` e `<=`, mudando o número de chamadas e a
conta do usuário. Fica declarado:

- **Escala interna: 8 casas decimais**, folgada para taxas cobradas por milhão
  de tokens, onde um turno pequeno custa frações de centavo.
- **A estimativa da próxima chamada arredonda para cima**, com
  `RoundingMode.CEILING`. O sentido é deliberado: arredondamento nunca deixa
  passar uma chamada que o teto não comportava. Errar para o lado do usuário é
  o único erro aceitável quando o outro lado é a fatura dele.
- **O custo observado também vai para a escala interna arredondando para
  cima** (`CEILING`), como a estimativa, e é nela que se soma e se compara.
  **Para exibir**, arredonda a meio para cima (`HALF_UP`), na escala de 2 casas.
  Exibição nunca alimenta soma nem comparação. Uma versão anterior desta frase
  mandava somar em 2 casas: uma chamada de US$ 0,004 somaria zero, e o teto
  nunca dispararia. Decisão do operador em 23/09/2026.
- **A comparação é `custo_acumulado + estimativa <= max_cost_usd`.** Igualdade
  **permite** a chamada. "Teto" é o valor máximo que se pode gastar, não o
  primeiro valor proibido — e gastar exatamente o que se autorizou é o que o
  usuário pediu ao escrever aquele número.
- Quando a chamada não cabe, a sessão **para antes de fazê-la** e o jornal
  registra o valor que a reprovou. Parar sem dizer quanto faltava é obrigar o
  usuário a adivinhar o próprio teto.

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
  mexe em bloco não declarado, e o teste **exige** a recusa. A auditoria do
  candidato final porta as suítes do Rust e acrescenta o que elas não cobrem
  por serem ASCII e só usarem `\n`: posição em bytes UTF-8, `\r` sozinho,
  espaço e dígito Unicode, fronteira de palavra do Rust, janela de contexto
  em bytes. As expressões regulares não usam `\s`, `\d`, `\w`, `\b` nem
  `(?U)`: no Android o `java.util.regex` é a ICU, em que
  `UNICODE_CHARACTER_CLASS` lança exceção, e na JVM dos testes aquelas classes
  são ASCII. Como a JVM não prova o que a ICU faz, o emulador do
  `:core:seguranca` (abaixo) roda também casos do `:core:protocolo` pela API
  pública (`ProtocoloNoAparelhoTest`): as expressões regulares e o
  decodificador de UTF-8 dos anexos, que no Android também não é o do OpenJDK.
- **`:core:provedores`, na JVM, com servidor HTTP de mentira.** Roda sem
  emulador e sem Robolectric porque o módulo é Kotlin puro e a chave chega por
  `FonteDeChave` (seção 4), satisfeita em teste por um valor em memória. Um
  `MockWebServer` por provedor cobre: corpo montado conforme o contrato da seção
  5.1, 429 com e sem `Retry-After`, timeout, e resposta sem `usage`. Sobre
  retenção, o caso é **em dois sentidos**, porque a regra tem dois lados: nos
  quatro provedores que expõem `store` — OpenAI, Gemini, xAI e Perplexity —, o
  corpo enviado **tem** `store: false`; nos dois que não expõem — Anthropic e
  DeepSeek —, o corpo **não tem** o campo. Exigir `store: false` nos seis, como
  uma versão anterior deste documento exigia, mandaria à Anthropic um parâmetro
  que o `/v1/messages` não declara. A política de nova tentativa de
  `provider_retry.rs` também é conferida com a contagem de requisições que
  chegaram ao servidor, porque cada tentativa é uma chamada paga — inclusive
  nos casos em que o próprio OkHttp repetiria a requisição sozinho (408, 503
  com `Retry-After: 0`, redirecionamento). Nenhum teste fala com provedor
  real.
- **`:core:seguranca`, instrumentado.** O Keystore só existe em aparelho ou
  emulador, então estes testes são instrumentados — e são poucos justamente
  porque a fronteira manteve tudo o mais fora deles. Cinco casos não podem
  faltar, e todos nascem das decisões da seção 6.2:
  1. o segredo cifrado com StrongBox volta em claro;
  2. **a falta do hardware de StrongBox cai no caminho sem ele e o registra**,
     em vez de estourar — exige encenar a ausência do hardware, porque teste que
     só passa no emulador que tem StrongBox não prova nada sobre o aparelho que
     não tem;
  3. **antes de autenticar, a decifra é recusada.** Sem este caso, uma
     implementação que esquecesse `setUserAuthenticationRequired(true)` passaria
     em todos os outros;
  4. **dentro da janela, decifra repetida funciona sem nova autenticação** — é o
     que distingue o modo por tempo do modo por operação, e sem ele a escolha do
     operador não está provada;
  5. **depois de a janela expirar, a decifra é recusada** e o estado observável
     é `pausada_aguardando_autenticacao`, não uma exceção crua.

  **Onde rodam, decisões do operador de 23/09/2026:** os casos 2 a 5 rodam na
  CI em toda PR, num emulador do Gradle Managed Devices (a solução oficial do
  Google), em job próprio que é **verificação obrigatória** do ruleset do
  repositório; no emulador da CI, pular é falha. O caso 1 exige StrongBox, que
  o emulador não tem, e **não há aparelho com o hardware disponível: o caso não
  é rodado.** O teste existe e roda em qualquer aparelho que tenha o hardware.
  A decisão foi conferida antes num emulador Android 17 (API 37), em
  23/09/2026:
  - o emulador não tem StrongBox (`FEATURE_STRONGBOX_KEYSTORE` falso, geração
    com `StrongBoxUnavailableException`), então no caso 2 a ausência do
    hardware é **real**, e não encenada; num aparelho com StrongBox o caso 2
    pula, e o caso 1 roda;
  - sem trava de tela o Keystore não gera chave presa à autenticação, então o
    teste define um PIN antes de cada caso (`locksettings set-pin`) — o que
    conta como autenticação — e autentica de novo sem tela
    (`locksettings verify`). Os casos 3 a 5 esperam a janela vencer de
    verdade, com janela de 3 s em teste.
  - **remover a trava de um aparelho de verdade apagaria as chaves presas à
    autenticação de todos os aplicativos dele.** Por isso os testes que
    definem e removem o PIN só rodam em aparelho sem trava nenhuma, como o
    emulador, e pulam em qualquer aparelho que já tenha uma; o caso 1 não
    toca na trava: exige aparelho já travado e desbloqueado nos cinco minutos
    anteriores.

  O caso 5 prova aqui o resultado tipado (`LeituraDaChave.ExigeAutenticacao`)
  e que a chave continua intacta depois de autenticar de novo; o estado
  `pausada_aguardando_autenticacao` que ele alimenta é do `:core:sessao`, e se
  prova lá.
- **`:core:sessao`, com Room em arquivo temporário.** Retomada depois de morte
  de processo é o caso que mais importa, e ele **não** pode usar banco em
  memória: banco em memória morre com o processo, então o teste estaria
  encenando apenas a recriação do *worker* e passaria sem tocar no que importa.
  O caso grava estado no meio da rodada num banco em arquivo, **descarta e
  reconstrói Room, WorkManager e o grafo de dependências**, e só então verifica
  que a deliberação continua do ponto certo — não do começo, e não de um ponto
  adiante.
- **`:core:sessao`, os tetos que protegem a fatura do usuário.** A seção 7 chama
  `max_cost_usd` de única barreira entre uma sessão mal configurada e a fatura
  do usuário; barreira sem teste é promessa. Quatro casos, cada um capaz de
  falhar:
  1. com saldo insuficiente para a próxima chamada **pela estimativa**, a sessão
     para **antes** de fazê-la — o teste conta as requisições que chegaram ao
     `MockWebServer` e exige que a última não tenha acontecido;
  2. **no limite exato, a chamada acontece** — `custo_acumulado + estimativa`
     igual a `max_cost_usd` permite, pela regra da seção 7.1, e o teste fixa
     isso para que ninguém troque `<=` por `<` sem que um teste caia. O par
     desse caso é o centavo seguinte, que tem de reprovar;
  3. resposta sem `usage` cai para a estimativa **e o jornal registra que o
     custo daquele turno é estimado**, não observado;
  4. o teto de tempo para a sessão do mesmo jeito, com o relógio injetado, e não
     com espera real.
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
- a chave de API é do usuário, é guardada apenas neste aparelho, cifrada por
  chave do Keystore não exportável, e é enviada por TLS **apenas ao provedor a
  que pertence** — a formulação exata está na seção 6.1, e "nunca sai do
  aparelho" não é usada porque é falsa;
- a chave nunca é exibida depois de gravada — a tela mostra "configurada" ou
  "não configurada", nunca o valor;
- o texto do usuário vai aos provedores que ele mesmo escolheu ativar, e a nada
  mais; o banco local e o segredo cifrado ficam **fora do backup do Android**
  (seção 4.2).

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
   barreiras, e ambos dependem de contagem que o provedor devolve. Mitigação em
   três camadas, porque uma só não basta: os tetos têm testes de fronteira
   próprios (seção 8); a **tela de sessão** é a superfície canônica de custo ao
   vivo e de cancelamento; e a notificação em primeiro plano espelha as duas —
   nunca as substitui, porque pode estar oculta (seção 4.1).
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

### Abertas, dependem de medição no aparelho

1. **O WorkManager basta, ou o serviço em primeiro plano tem de ser nosso?**
   Duas medições que levam à mesma decisão, e por isso viram uma pendência só.
   A primeira é a frequência com que a cota de *jobs* do Android 16 corta
   sessões de uso normal. A segunda é quanto se perde quando o teto de seis
   horas chega: o `Service.onTimeout()` vai para o serviço interno do
   WorkManager e não para o nosso código (seção 4.1), então o que protege é o
   *checkpoint* por turno — falta medir se `onStopped()` chega a tempo de
   rotular a pausa, ou se o processo morre antes. Se qualquer das duas
   decepcionar, a troca é **serviço em primeiro plano do próprio aplicativo**,
   em que `onTimeout()` e `stopSelf()` são nossos. Isso muda o agendamento, não
   o resto do desenho.
2. **O prazo por chamada, com o raciocínio no máximo, nos seis provedores.** O
   prazo canônico é 120 s (`PRAZO_POR_CHAMADA` no `:core:provedores`), pensado
   para o esforço padrão e 20 mil tokens de saída. Com o raciocínio no máximo e
   até 64 mil tokens de saída sem *streaming*, é provável que ele estoure com
   frequência em qualquer dos seis. Isso tem dois efeitos que o `:core:sessao`
   precisa tratar: a chamada que estourou o prazo **pode ter sido cobrada sem
   devolver o uso**, e a sessão tem de lançar no acumulado o custo **estimado**
   dela, e não zero; e a política canônica repete o erro de rede uma vez, o que
   pode cobrar duas vezes. Na Perplexity há um agravante: com `store: false`
   não há modo assíncrono, e a medição de 17/09/2026 registrou o caminho
   síncrono passando de 40 s sem responder. O prazo precisa ser calibrado com
   medição, não arbitrado. Na mesma medição entra o teto de
   saída: a Perplexity não publica o máximo de saída do `perplexity/sonar`,
   que tem 128 mil de contexto, e os 64 mil da seção 5.1 somados a um pedido
   longo podem passar dele. Se passarem, a chamada volta com erro HTTP do
   provedor, classificado como tal, e o teto dela tem de baixar.
3. **O valor da janela de autenticação e o teto de sessão.** A janela é fixa na
   geração da chave (seção 6.2) e o `dataSync` não passa de seis horas em 24
   (seção 4.1). O número que o produto adota tem de sair de sessões reais, e
   trocá-lo depois exige recifrar o segredo — então não é escolha para ser
   revista sem custo.

Uma pendência de medição **saiu daqui por estar documentada, não por ter sido
medida**: o teto do `dataSync`. Uma versão anterior afirmava que a documentação
não o declarava e mandava medir em aparelho. Declara, na página de mudanças de
comportamento do Android 15, e agora está na seção 4.1. Ausência de um fato em
duas páginas não é ausência do fato.

### Aberta, por decisão do operador

- **Conectores extras de busca de evidências.** O desktop aceita, além do
  Crossref e do OpenAlex, conectores configurados num arquivo JSON, com a
  chave de API lida de uma variável de ambiente do processo — que o Android
  não tem. Decisão do operador de 24/09/2026 (MAEANDR-18): a v1 usa só os
  dois embutidos. Se os extras vierem, a chave de cada um vai para o cofre do
  `:core:seguranca`, como as dos seis provedores, e uma tela os cadastra.

### Resolvidas nesta especificação

- **StrongBox: liga ou não?** Resolvida pelo operador em 21/09/2026: **liga**,
  com caminho de degradação registrado para aparelho sem o hardware. Seção 6.2.
- **Vínculo com autenticação do usuário: por tempo ou por operação?** Resolvida
  pelo operador em 21/09/2026: **por tempo, até a entrega do texto final**.
  Seção 6.2.
- **Quantos módulos por entrega?** Resolvida pelo operador em 21/09/2026:
  **quatro entregas**, na ordem `:core:protocolo` → `:core:provedores` →
  `:core:sessao` → `:app`, cada uma com seus testes e portão verde.
  `:core:seguranca` é pequeno e viaja junto com `:core:provedores`, que é quem
  precisa dele. A calculadora foi em três PRs para ~1.300 linhas; aqui são
  ~6.700 e cinco módulos.
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
  no rastreador do `admin-app`: Linear ADMIAPP-28 e a gêmea
  `LCV-Ideas-Software/admin-app#646`, com prioridade Alta e prazo 27/09/2026.
  Aqui fica só a referência cruzada.

---

Registro por Claude Code (Claude Opus 5), sob direção do operador.
