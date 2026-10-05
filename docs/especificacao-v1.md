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

**Produto para público externo, não cópia.** Dito pelo operador em 25/09/2026:
os aplicativos Android são portes dos originais, mas destinados a público
variado e externo; não devem depender da estrutura interna dele
(`admin-app/MainSite`, D1, Secret Store, convenções de uso próprio) e não são
cópias idênticas. Tolerância ou atalho do original que só faz sentido no
ambiente interno não se porta; onde o uso por terceiros pede outra solução
(seções 2.2 e 4.4), a especificação diz qual, e a decisão fica registrada.

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
estimativas. **Medidas de novo em 25/09/2026 sobre o `c70dc54f` (4.860
linhas)**, antes da terceira entrega: a medição de 21/09/2026 era sobre o
arquivo de 4.705 linhas, e quatro pull requests de 22/09/2026 (as de
número 652, 657, 658 e 659) moveram todas as unidades. As unidades e os destinos não
mudaram; só as linhas.

| Unidade | Linhas | Destino no Android |
| --- | --- | --- |
| Tipos de domínio e saneamento de entrada (38–450) | 413 | porta |
| Esquema do D1 e migrações — `ensureSchema` (451–674) | 224 | vira Room |
| Leitura tolerante e estrita de JSON; configurações, taxas, modelos e agentes ativos (676–780) | 105 | porta |
| Projeção pública de sessão e artefato (782–833) | 52 | vira modelo de UI |
| Artefato em markdown e versionamento (835–921) | 87 | porta |
| Guarda de segredo — Cloudflare Secret Store (923–1042) | 120 | **substituída** — Keystore, seção 6 |
| Custo estimado e observado (1044–1081) | 38 | porta, em `BigDecimal` |
| Tempo de sessão e tetos (1083–1097) | 15 | porta |
| Protocolo: leitura e validação do relatório do agente (1099–1594) | 496 | porta |
| Guarda de qualidade e escalonador de revisores (1596–1723) | 128 | porta — a guarda está no `:core:protocolo`; o escalonador entra com a orquestração |
| Auditoria de links e defesa de SSRF (1725–2041) | 317 | porta **do Rust atual**, com o modelo de ameaça invertido — seção 5.4 e o parágrafo abaixo |
| Auditoria do candidato a release final (2043–2079) | 37 | porta **do Rust atual**, em cinco estágios — parágrafo abaixo |
| Montagem dos prompts de rascunho e revisão (2081–2298) | 218 | porta |
| Rede: timeout, retry, tratamento de 429 (2300–2471) | 172 | porta |
| Resolução de modelo e cliente Vertex (2473–2556) | 84 | **substituída** — API geral, seção 2.4 |
| Chamada aos provedores e montagem do corpo (2558–2904; `RESUMABLE_STATUSES` em 2828–2842 porta) | 347 | **reescrita** nas APIs novas — seção 5 |
| Persistência da sessão (2906–3009) | 104 | vira Room |
| Estado da revisão circular e retomada (3011–3318) | 308 | porta |
| `runSession` — a orquestração (3320–4300) | 981 | **portado** (PR 3b, `Deliberacao`), com os acréscimos do desktop da seção 4.2; é o coração do produto |
| Rotas HTTP e varredura de sessões velhas (4302–4860) | 559 | **o transporte desaparece** — seção 2.4; as regras de negócio das rotas (`resolveStartRequest`, o insert da sessão, a validação das configurações, a troca de conteúdo, cancelar, retomar e a varredura) portam para o `:core:sessao` |

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

**O que a PR 4b implementa disto (29/09/2026).**

- **Manifesto de citações:** a tela de anexos da sessão anexa pelo seletor de
  documentos do sistema, mostra na hora o que a sessão vai ler do manifesto e
  remove. O formulário de nova sessão aceita um manifesto opcional, gravado
  junto com a sessão, sob a mesma trava da reconciliação: o arquivo é
  publicado antes, e a linha da sessão, o primeiro evento e a linha do anexo
  entram numa transação só. Se a gravação falha, nenhuma sessão fica na fila
  sem o manifesto para a reconciliação retomar (achado do Codex na #78); o
  disco que falha vira recusa com a frase do desktop (`failed to write
  attachment: …`), aqui e na tela de anexos. Um manifesto que a sessão
  recusaria impede o início — o ilegível, o desvinculado do protocolo ativo e
  o que ainda está sendo lido —, e no início ele é lido de novo contra o
  protocolo que a sessão vai receber, porque as configurações podem ter
  mudado depois da escolha. O arquivo é lido só pelo `ContentResolver`, sem
  permissão de armazenamento, e o teto de 16 MiB vale durante a leitura. Com a
  sessão na fila ou em execução, os anexos não mudam: a tela confere antes, e
  o núcleo confere de novo na transação que grava ou remove a linha, porque a
  leitura lenta de um provedor de documentos deixa a sessão ser retomada no
  meio (achado do Codex na #78).
- **As linhas de link de uma sessão:** o registro de links é global, como no
  canônico, porque o id de cada linha já leva a impressão da origem.
  `LinksDaSessao` acha as da sessão pela impressão do texto que ela tem agora
  — o final, se convergiu, senão o atual —, que é o SHA-256 que a auditoria
  grava.
- **Revisão:** a tela de links auditados porta o painel de integridade do
  desktop (`LinkIntegrityPanel.tsx`, `maestro-app` `0e17817`), com os rótulos
  e os avisos dele palavra por palavra. A recusa do motor vai no aviso, depois
  da frase do desktop, que a engole. O revisor é `operator`, a nota tem pelo
  menos dez pontos de código, e a decisão vale contra a URL e o hash que a
  tela mostrou. Com a sessão na fila ou em execução, a revisão e as propostas
  esperam (decisão 24 do operador, 29/09/2026): a auditoria da sessão regrava
  as mesmas linhas e guarda o que leu, e uma decisão feita nesse meio-tempo
  podia se perder ou não chegar ao revisor. A captura segue liberada, porque
  não mexe nas linhas de link. O registro é global, e duas sessões com o mesmo
  texto dividem as linhas: a tela grava por `LinksDaSessao.registroDaTela`,
  que recusa, na transação da gravação, a linha do texto de qualquer sessão na
  fila ou em execução (achado do Codex na #78). A linha e a entrada do diário
  de auditoria (`anotar`) são gravadas na mesma transação, na auditoria, na
  decisão e nas propostas: a linha nunca muda sem a entrada que a explica.
  Divergência do canônico, que grava os dois arquivos em sequência (achado do
  Codex na #78).
- **Captura assistida pelo operador:** `ImportacaoDoOperador`, no
  `:core:provedores`, porta `handoff_record`,
  `open_web_evidence_in_default_browser` e `import_operator_evidence`, com as
  regras de nome, tipo, tamanho e bytes mágicos. **Abrir no navegador** valida
  a URL pela mesma regra de rede pública da coleta, monta o registro de
  passagem, grava-o e só então entrega a URL validada ao navegador do sistema
  (`ACTION_VIEW`); o navegador que não abre, ou cujo disparo uma política
  barra (`SecurityException`), fica anotado no registro, gravado de novo depois
  do disparo. O desktop grava uma vez, depois do disparo; aqui o registro vai
  antes, porque o Android pode matar o aplicativo depois do `startActivity`, e
  sem ele não há disparo (achado do Codex na #78). O arquivo salvo volta
  pelo seletor de documentos e vira evidência fornecida pelo operador, sob o
  endereço do link para o qual o seletor foi aberto; se esse link saiu da
  lista enquanto o seletor estava aberto, nada é importado; o disco que falha
  ao guardar o arquivo é a falha da importação, com o erro no aviso, como o
  desktop devolve o de `write_binary_file`. Nenhum dos dois registros toca a linha de
  link — no desktop também não: só a revisão explícita a libera. O tipo do
  arquivo segue o `captureMediaType` do desktop, e o `application/octet-stream`
  com que o Android informa uma extensão desconhecida conta como sem tipo.
  Sem `<queries>` no manifesto: a documentação oficial de visibilidade de
  pacotes diz que `startActivity` não depende dela para abrir uma URL, e o
  aparelho sem navegador chega como `ActivityNotFoundException`, que o
  registro anota (decisão do operador de 29/09/2026, que revogou a parte da
  emenda A10 do plano do `:app` que pedia a declaração).
- **Propostas de correção:** Crossref ou OpenAlex, fora da linha principal.
  Cada resultado é guardado como evidência, como o canônico faz, com o item
  JSON como corpo; o disco que falha ao guardar um deles é a falha da busca.
  As propostas só sobrevivem à auditoria seguinte numa linha
  decidida (`preservarRevisao`, igual ao canônico), e por isso a tela pede a
  decisão depois de propor. Sair da tela cancela a busca em curso, e nada é
  gravado depois: sem chamada HTTP para interromper, a política de rede da
  busca barra o resultado seguinte ao validar a URL dele, a busca confere o
  cancelamento antes de entregar os resultados ao motor, e o motor só grava a
  linha com a tela viva (achado do Codex na #78).
- **A lista acompanha o texto e a auditoria:** com a tela aberta durante a
  execução, a lista é relida quando o texto da sessão muda e quando a
  auditoria regrava as linhas ou as evidências do mesmo texto (o
  `InvalidationTracker` do Room sobre as duas tabelas); se o link aberto sai
  dela, a nota e a decisão digitadas para ele são apagadas e nunca vão para
  outro link. O mesmo quando a linha aberta volta com outra URL ou outro hash,
  como depois da auditoria de outra sessão com o mesmo texto: o julgamento
  digitado era para outro conteúdo. Ao fim de uma ação, só sai do formulário o
  que ela enviou, e o que se digitou enquanto ela corria fica. A releitura que
  falha por armazenamento mantém a lista que a tela tinha, com o aviso, aqui e
  na tela de anexos, sem passar por falha da gravação que já foi feita
  (decisão 25), e vale a releitura mais nova que terminou bem: uma anterior que
  termine depois de outra já aplicada não repõe a lista velha, e uma mais nova
  que falha não descarta a anterior que deu certo. Montar a busca de propostas
  e consultar a evidência já guardada, na importação, leem o Room e também
  passam pelo tratamento de armazenamento. Achados do Codex na #78 e da revisão
  antes do push da rodada 10.
- **Decisão 23 do operador (29/09/2026):** todo turno de revisão cujo texto
  atual reprova na auditoria final leva ao revisor o pacote do portão — as
  linhas que falharam e os candidatos de correção. Antes, o pacote só ia numa
  tentativa corretiva, que só acontece por violação de contrato: um `READY`
  sobre texto reprovado era recusado e redesenhado sem explicação, e o
  aparelho não tem editor. Essa auditoria pode ir à rede, link por link, e
  por isso o teto de tempo é conferido de novo depois dela, antes da chamada
  paga.
- **Decisão 25 do operador (29/09/2026):** numa ação de tela, o banco cheio, o
  erro de disco do SQLite e o arquivo que não grava são a falha da ação, com o
  motivo no aviso, e não derrubam o aplicativo — em todas as telas que gravam:
  iniciar, cancelar e retomar a sessão, salvar as configurações, anexar e
  remover, e a passagem ao navegador, a importação, a decisão e as propostas
  de um link. Um classificador só (`motivoDeArmazenamento`) decide o que é
  falha de armazenamento; o resto, inclusive o cancelamento da corrotina,
  segue adiante. A pergunta veio de três rodadas seguidas do Codex na #78 que
  acharam, cada uma, mais uma tela que caía; o trabalho em segundo plano já
  levava qualquer exceção a `error` da sessão. A regra vale para tudo o que a
  ação lê e grava, inclusive o que ela lê ao se preparar (as configurações ao
  abrir a retomada, ao escolher o manifesto e ao testar as chaves; a evidência
  já guardada, na importação; o e-mail de contato, ao montar a busca), e o
  aviso diz o que de fato aconteceu: o cancelamento e a retomada releem a
  linha dentro da própria transação, e uma falha ali desfaz tudo, em vez de
  deixar gravado o que o aviso diz que não foi; o cofre das chaves devolve o
  motivo da falha ao guardar e ao remover; a exportação só diz que nada foi
  salvo quando apagou o documento que o seletor criou; e a passagem cujo
  resultado não foi anotado diz se o navegador abriu. A varredura de todas as
  ações de tela, antes do push da rodada 10 da #78, achou esses casos.
- **Decisão 25 estendida (30/09/2026,
  [#80](https://github.com/LCV-Ideas-Software/maestro-android/issues/80),
  MAEANDR-27): "aviso e segue".** As leituras de abrir e de voltar a uma tela,
  a observação ao vivo do Room e a reconciliação da abertura entram na regra.
  O armazenamento que falha numa leitura é um aviso com o motivo, e a tela
  fica com o que mostrava; se nunca leu, mostra no lugar o motivo ("Não foi
  possível ler os dados desta tela; ela tenta de novo quando você voltar a
  ela"), e não um vazio que pareça o estado real ("Sessão não encontrada",
  "nenhuma sessão", o formulário de configurações em branco). A volta da
  tela ao primeiro plano lê de novo, sem laço; a abertura não conta como
  volta. Um disco que derruba várias leituras dá um aviso por volta, e um
  toque ou uma ação são um pedido novo, que reabre o aviso, inclusive o toque
  de novo no mesmo artefato. A observação recriada que falha (a volta depois
  de mais de 5 s fora do primeiro plano) entrega de novo o que a tela
  mostrava, para a tela não congelar; e a leitura não observada que falhou (a
  parada no WorkManager, a lista de links) espera a volta seguinte, e não é
  refeita a cada gravação de outra sessão. Toda releitura que pode se
  sobrepor a outra é ordenada (a lista dos anexos e a dos links, as
  configurações da tela inicial e as das Configurações, os agentes prontos da
  sessão, o cofre, as Licenças): a que termina bem depois de uma mais nova já
  aplicada não repõe o que ela trouxe, e a falha de uma já superada por outra
  mais nova que deu certo não decide nada, nem aviso, nem motivo, nem essa
  espera; um toque em Retomar durante a abertura não abre outra (revisão da
  #81). O classificador
  passa a reconhecer também o banco que não abre e o corrompido
  (`SQLiteCantOpenDatabaseException`, `SQLiteDatabaseCorruptException`) e
  desembrulha a `ExecutionException` com que o `get()` do WorkManager entrega
  o erro do banco dele; mora no `:core:sessao`, e as telas e o núcleo da
  sessão usam o mesmo, inclusive para a falha que o WorkManager entrega na
  inicialização (seção 4.3). Vale em todas as telas: a inicial (a lista, os eventos da sessão do
  topo e as configurações, o cofre e o orçamento; sem configurações lidas, o
  Iniciar é recusado com o motivo), a da sessão (a sessão e os eventos, os
  autos e o artefato escolhido, a última parada no WorkManager, que falhando
  mantém o rótulo sem parar o resto da tela, e os agentes prontos),
  Configurações (lidas a cada volta até carregar; depois, nem a volta nem
  uma segunda leitura que termine depois da primeira sobrescrevem o que se
  digitou), Licenças (os quatro textos lidos fora da
  linha principal), Anexos, Links e Texto final (a exportação sem a sessão
  lida dá o motivo do armazenamento). O que nunca foi lido não aparece como o
  gravado: os cartões da tela inicial dizem "Não lido" em vez de "Sem
  sessão", "0 / 6" ou "US$ 0.00". O cancelamento pela notificação cuja
  gravação falha por armazenamento é uma notificação com o motivo, e o
  trabalho segue: parar o trabalho sem a transição é o que a emenda A3
  proíbe. Ficam declarados dois resíduos: a ligação de produção da
  reconciliação da abertura, que o teste exercita pelo mesmo objeto, mas não
  com o banco do processo; e a volta depois de mais de 5 s fora do primeiro
  plano com o disco ainda falhando, em que a leitura recomeçada em ON_START
  falha antes do ON_RESUME e a volta a relê: dois avisos e duas leituras
  nessa volta (a corrida do `WhileSubscribed`, aceita no desenho).
- **Decisão 26 do operador (01/10/2026,
  [#82](https://github.com/LCV-Ideas-Software/maestro-android/issues/82),
  MAEANDR-28):** o acesso que o provedor de documentos nega ao documento
  escolhido no seletor (`SecurityException`), na leitura (os anexos, a captura
  dos Links e o manifesto do formulário de nova sessão) e na exportação do
  texto final, é falha de armazenamento: passa pelo classificador, numa
  entrada própria da fronteira do documento (`motivoDoDocumento`), e o aviso
  traz o motivo. O arquivo escolhido que não se lê também passa a dizer o
  motivo, e não só "Não foi possível ler o arquivo escolhido.". Fora dessa
  fronteira, um `SecurityException` (uma permissão do sistema que falta) é
  defeito de código e segue adiante.
- **Decisão 27 do operador (01/10/2026, #82, MAEANDR-28):** o cancelamento
  pela notificação segue a regra da tela da sessão. O trabalho só é cancelado
  com o cancelamento gravado; a recusa (a sessão já finalizada, que não existe
  ou que mudou de estado no meio) não cancela o trabalho e vira a mesma
  notificação do cancelamento que não gravou, com a mensagem da recusa. Antes,
  a recusa sem exceção cancelava o trabalho. O texto da notificação deixa de
  dizer que a sessão seguiu como estava, o que a recusa não garante. As duas
  perguntas saíram da revisão da #81.
- **Lacuna medida, fora desta entrega:** no canônico atual (`0e17817`,
  MAESTRO-34), a revisão confere também a URL final e a cadeia de
  redirecionamentos; o motor do Android, portado de `68528f9`, confere a URL e
  o hash. O porte está na
  [#77](https://github.com/LCV-Ideas-Software/maestro-android/issues/77)
  (MAEANDR-26).

Onde o porte é **mais estrito que o canônico**, de propósito. Salvo os dois
últimos, que são decisões do operador, os pontos corrigem defeitos que a
revisão do Codex achou na PR #57 e depois do merge dela, e que também estão
no Rust em `68528f9`:

- **só se aceita link cuja verificação mecânica passou.** O Rust só conferia o
  código HTTP: página de captcha, de login ou de paywall, ou evidência
  bloqueada, servida com 200, podia ser aceita como suporte. Só passa evidência
  pronta e sem interação pendente. Na fila, em coleta, vencida ou à espera do
  operador, ou com pedido de consentimento ou de confirmação de download, ela
  vai para quarentena, qualquer que seja o código HTTP guardado nela de uma
  coleta anterior. A linha conta como bloqueada sempre que alguém precisa
  agir antes de a evidência valer (bloqueada, coleta que não terminou, ação
  do operador, interação pendente, captcha incluído) — o mesmo predicado da
  quarentena; só a coleta que terminou sem pendência e falhou conta como erro.
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
- **acima de 500 citações, aspas, sinais de um tipo, referências ou marcações
  de HTML cru, o texto é recusado.** O Rust para de ler no limite e ignora o
  excedente em silêncio;
- **valor que dobra para vazio nunca está presente no texto.** Só pontuação,
  ou só letras que o dobramento ASCII descarta, dobram para vazio, e o
  `contains("")` do Rust é verdadeiro para qualquer texto: citação,
  referência, marcador de nota ou chave de autor ausentes passavam por
  presentes. Uma regra só, em toda comparação: valor sem letra nem dígito
  nunca está presente (letra e dígito contados por ponto de código, para a
  letra fora do plano básico não passar por pontuação); valor de que o
  dobramento descarta alguma letra ou dígito — um nome grego, mesmo
  misturado com ASCII (`Ωμέγα, 2020` dobra para `2020`) — é comparado pela
  chave canônica, e o mínimo de quatro letras do primeiro autor é medido nessa
  mesma
  representação, contando só letras e dígitos;
- **HTML cru no texto final bloqueia a liberação** (`raw_html_in_final_text`;
  decisão do operador de 25/09/2026). O texto final é Markdown sem HTML:
  qualquer tag, comentário, instrução de processamento, declaração ou CDATA,
  em linha ou em bloco, que a especificação CommonMark reconheça como HTML
  cru (seções 4.6 e 6.6, lidas pela `commonmark-java` — decisão do operador
  de 24/09/2026, no lugar de um reconhecimento escrito à mão) é recusado,
  apontando o trecho. `<` na prosa (`2 < 3`), link automático e código não
  são HTML cru. Não há máscara: as aspas são procuradas no texto tal qual. É
  também o que impede o modelo de injetar marcação no que o aparelho exibe
  (seção 4.4). O Rust tolerava o HTML e pulava a aspa reta depois de qualquer `<` sem `>`
  adiante, ou logo depois de um `=`: prosa como `2 < 3 e "..."` escondia uma
  citação direta sem fonte, e a aspa que fecha um atributo pareava com a que
  abre o seguinte;
- **o site-local IPv6 (`fec0::/10`) é recusado; o IPv4 dentro do NAT64
  bem-conhecido (`64:ff9b::/96`) e do 6to4 (`2002::/16`) é julgado como
  IPv4; e o prefixo NAT64 de uso local (`64:ff9b:1::/48`, RFC 8215) é
  recusado inteiro** (decisão do operador de 24/09/2026): ele admite qualquer
  comprimento do RFC 6052, o endereço sozinho não diz qual, e existe para a
  tradução local. O bem-conhecido não é recusado inteiro porque, numa rede
  com DNS64, todo site só IPv4 resolve para ele. O Rust deixava todos
  chegarem à rede local do usuário;
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
  dois leitores do mesmo arquivo veriam manifestos diferentes;
- **o manifesto desvinculado do protocolo é recusado antes do primeiro turno
  pago** (achado do Codex na #78). No desktop, a interface fixa o hash do
  protocolo ao importar o arquivo; aqui é o usuário quem escreve o
  `protocol_hash`, e a auditoria só descobria o erro na primeira revisão, com
  o rascunho já pago. As quatro regras de `validate_manifest` que não
  dependem do texto — esquema, vínculo com o hash do protocolo ativo,
  capacidade e hash ausente — são conferidas pela sessão antes de começar e
  pelas telas de anexos e de nova sessão (nesta, de novo no início, contra o
  protocolo que a sessão recebe), com o hash esperado na mensagem; a
  auditoria continua a dá-las, na mesma ordem.

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

#### O que a PR 4a implementa disto (28/09/2026)

O `:app` é Jetpack Compose com Material 3, uma `Activity` só e a Navigation 3
(a regra do plano mandava usá-la se houvesse versão 1.x estável na página
oficial no dia, e havia: 1.2.0, de 23/09/2026). A `Fabrica` do `:core:sessao`
é a raiz de composição, sem Hilt (decisão 18 do operador, 28/09/2026). O
porte segue o modelo Proton: mesmo produto, mesmos rótulos, moldura da
plataforma. Cada unidade do cliente web tem destino:

| Unidade do web | Destino na 4a |
| --- | --- |
| Os nove tipos de domínio | as projeções do `:core:sessao` (`ProjecaoDaSessao`, `ResumoDoArtefato`, `DetalheDoArtefato`, `Configuracoes`, `EventoDaSessao`); a tela não tem tipo próprio além do estado de interface |
| `statusLabel`, `agentLabel`, `isRunning`, `isResumable` | `Rotulos`, Kotlin puro, com os 18 rótulos do web e o 19.º, "Pausada aguardando autenticação"; `isResumable` usa `Estados.RETOMAVEIS`, que inclui a pausa de autenticação |
| `eventDate`, `formatBytes`, `simpleDiff` | `Formatos` e `Diff`, com as fronteiras e o corte de 220 linhas do web |
| As 20 chamadas `fetch` e a sondagem de 4 s | `Flow` do Room (`observarTodas`, `observar`, `observarEventos`) e chamadas aos repositórios fora da linha principal |
| Botões **Atualizar**, **Atualizar autos**, **Recarregar** | **saem**: a tela observa o banco |
| Métricas **Sessão**, **Com o trabalho agora**, **Agentes prontos**, **Teto configurado** | tela inicial, sobre a sessão mais recente; a tela da sessão ganha **Custo acumulado** ao vivo com o teto da linha |
| **Nova sessão** (campos, padrões e as quatro validações) | tela inicial, com as mensagens do web; depois delas, a permissão de notificações e a autenticação (seções 4.1 e 6.2) |
| **Sessões recentes** | tela inicial, observada, na ordem e no corte do `GET /sessions` do web (a tocada por último primeiro, 30) |
| **Cancelar** / **Retomar** | tela da sessão; retomar abre o diálogo com líder e colegiado (as duas validações do web) e, numa pausa por custo, o teto novo |
| **Rastreamento** (últimos 8 eventos) | tela da sessão, com "Mostrar todos os eventos" |
| **Autos / Evidências** e as cinco abas | tela da sessão: Texto, Diff, Relatório, Links e Metadados (os onze campos do web, no formato de `JSON.stringify(…, null, 2)`); a aba Links da 4a listava a auditoria por artefato, que a deliberação do aparelho nunca grava, e a 4b a troca pela tela de links da sessão |
| **Texto atual / Texto final** | tela da sessão, monoespaçado como o `<pre>` do web |
| **Criar Post** e o editor do MainSite | **substituído** pela tela do texto final com exportação (seção 4.4), que é da PR 4b |
| **Chaves dos agentes** | configurações: cada chave vai ao cofre do aparelho pelo botão da própria linha, nunca volta à tela nem fica com o serviço de preenchimento automático (o `autoComplete="off"` do web, pelo `AutofillManager.cancel` que a documentação do Compose indica), e a pílula tem o terceiro estado "não verificável" (seção 6.2) |
| **Testar chaves** | **porta**, atrás de uma confirmação que diz o custo e da autenticação (decisão 20 do operador, 28/09/2026); a regra é o `TesteDeChaves` do `:core:sessao` |
| **Custos / Valores dos tokens** | configurações; limite de tempo de 1 a 300 minutos (seção 4.1) |
| **Modelos** | só leitura: o modelo é fixo por provedor |
| **Protocolo editorial** | configurações, salvo no aparelho |

O que só existe no Android, também na 4a: o contato opcional para o Crossref
(seção 5.4), o estado da permissão de notificações com o atalho para os
ajustes, a linha que diz onde vive a chave do Keystore, o aviso de aparelho sem
trava de tela, a tela de licenças e o ícone do aplicativo com a marca da LCV
Ideas & Software (decisão 21 do operador, 28/09/2026).

Desvios de aparência declarados: tema só claro, como o web; a fonte Inter não
vem embutida (fica a sem serifa da plataforma); as grades de duas colunas
viram uma coluna; os ícones são os Material Symbols oficiais do Google no
lugar dos do Lucide, com o mesmo sentido; a linha escolhida de uma lista ganha
a borda de foco do web, porque o fundo que o web troca (8 % contra 10 %) não se
distingue num telefone; os textos com "(s)" do web viram plurais da plataforma.

#### O que a PR 4b implementa disto (29/09/2026)

| Unidade | Destino na 4b |
| --- | --- |
| **Criar Post** e o editor do MainSite | a tela do texto final, formatado e exportável em Markdown, TXT e PDF (seção 4.4) |
| Aba **Links** dos autos | explica que a auditoria de links do aparelho é da sessão, no portão da auditoria final, e leva à tela de links auditados (seção 2.2) — a lista por artefato da 4a ficava sempre vazia |
| O painel de integridade de links do desktop (`LinkIntegrityPanel.tsx`) e a captura do operador da tela de evidências (`EvidenceScreen.tsx`), `maestro-app` `0e17817` | a tela de links auditados da sessão: a lista, o link aberto com a ficha, as evidências do endereço, a captura do operador, os candidatos de correção e o julgamento (seção 2.2) |
| Os anexos de sessão do desktop (`citation_manifests_from_attachments`) | a tela de anexos, com o manifesto de citações, e o campo opcional do formulário de nova sessão (seção 2.2) |

O que o web e o desktop não têm e fica de fora: os filtros e a paginação do
inventário global de links do desktop — a tela é de uma sessão só, e mostra
todas as linhas do texto dela. Desvio de aparência declarado: a linha da
lista mostra a âncora, a classificação e a revisão numa linha cada, como as
outras listas do aplicativo; o contexto, que o desktop também põe na linha,
fica na ficha do link aberto, porque na pílula de largura de telefone ele
tomaria várias linhas e a borda arredondada cortaria o começo delas.

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
  2.27.1 suporta o Kotlin 2.4.20 ([registro oficial de
  mudanças](https://github.blog/changelog/2026-09-25-codeql-2-27-1-adds-c-and-c-query-and-kotlin-2-4-20-support/)), e a configuração padrão analisa `java-kotlin`
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

1. `compileSdk` e `targetSdk` 37, `minSdk` 34. Desde 04/10/2026, o `minSdk` é
   36, por decisão do operador para todos os aplicativos \*-android (seção 6.2).
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
   pela tela. **O teto é 300 minutos** (decisão do operador de 25/09/2026):
   cinco horas, uma de folga sob as seis do orçamento agregado, para que uma
   segunda sessão no mesmo dia não morra sem aviso. O web aceita até 720. O
   número é a constante `TETO_DE_MINUTOS` do `:core:sessao` e é revisável
   quando sessões reais forem medidas (seção 11).
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

#### O que a PR 3b implementa disto (27/09/2026)

`TrabalhoDaSessao` é o `CoroutineWorker`: sobe o serviço `dataSync` com
`setForeground()` **antes** de qualquer chamada paga (se o sistema recusar,
`Result.failure()` sem tocar na sessão; a reconciliação da seção 4.3 a marca),
corre a `Deliberacao` e devolve `Result.success()` em **todo** desfecho — uma
pausa ou um erro é a sessão parada num status retomável, nunca uma nova
tentativa do WorkManager, que repetiria um rascunho pago. O `Agendador` põe um
trabalho único por sessão (`enqueueUniqueWork("sessao-<id>", KEEP)`, rede
exigida); a `FabricaDeTrabalhos` é a `WorkerFactory` oficial que o `:app`
instala. Uma parada pelo sistema chega como cancelamento da corrotina: o estado
já está no *checkpoint*, e o worker só **rotula** a pausa com
`getStopReason()` no jornal, sob a cerca da execução, da melhor forma possível.
O WorkManager reenfileira o trabalho parado, e a segunda execução passa por
`preparar`, que recusa seguir sobre uma chamada paga sem resultado (decisão 16,
seção 4.2). A notificação mostra rodada, agente e custo, e é atualizada a cada
*checkpoint*; a ação de cancelar **não** é o `createCancelPendingIntent` do
WorkManager, que só para o trabalho e deixaria a linha `running` para a
reconciliação retomar e pagar de novo: ela vai a um receptor da biblioteca
(`CancelamentoDaSessao`) que grava `blocked_cancelled` no Room primeiro e só
então, com o cancelamento gravado, cancela o trabalho, para a chamada em voo
parar logo (revisão cruzada de 27/09/2026 sobre o plano da 3b, emenda A3). A
recusa (a sessão já finalizada ou que mudou de estado) não cancela o trabalho
e vira uma notificação com a mensagem dela, como na tela da sessão (decisão
27). A biblioteca declara
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` e a fusão do tipo no
serviço interno do WorkManager; `POST_NOTIFICATIONS` fica com o `:app`. O
saldo do orçamento de seis horas é `Orcamento.restanteNaJanela`, sobre as
execuções que tocam a janela de 24 horas, para a tela avisar antes de uma
segunda sessão longa morrer sem explicação.

#### O que a PR 4a implementa disto (28/09/2026)

- **A permissão de notificações** é pedida quando a pessoa toca **Iniciar
  sessão** sem tê-la concedido, com a explicação antes do pedido do sistema, e
  a sessão começa qualquer que seja a resposta: sem a permissão, perde-se só o
  aviso na gaveta. As configurações mostram o estado e levam aos ajustes.
- **O aviso do orçamento de seis horas** aparece no formulário quando o que
  resta na conta das execuções deste aplicativo nas últimas 24 horas é menor
  que o limite de tempo da sessão, ou menor que uma hora quando não há limite.
  O texto diz que a conta é a do aplicativo, e não a cota que a plataforma
  guarda. É aviso, não recusa.
- **O rótulo da parada** (`getStopReason()`) aparece no cartão de erro da
  sessão, como informação de produto; e o erro da decisão 16 ganha a frase que
  diz que retomar pode pagar a mesma chamada de novo.
- **O toque na notificação abre a tela da sessão**, por uma `PendingIntent`
  imutável e explícita, com código próprio por sessão, separado do da ação de
  cancelar; a `Activity` é `singleTask`, e o pedido chega por `onCreate` ou
  `onNewIntent`.
- **A reconciliação da seção 4.3 roda a cada entrada do processo em primeiro
  plano** (`ProcessLifecycleOwner`, `ON_START`), sob a mesma trava que as telas
  usam entre gravar uma sessão e enfileirá-la, para uma linha recém-criada não
  ser varrida como "sem trabalho vivo" nesse intervalo.
- **O WorkManager é inicializado sob demanda**, com a configuração do
  `Application` e a `FabricaDeTrabalhos`; o inicializador automático sai do
  provedor do App Startup. A `Fabrica` só toca o WorkManager no primeiro uso do
  agendador, depois de instalada: a primeira chamada a `getInstance` pode
  começar na hora um trabalho pendente, e o worker precisa encontrar a fábrica
  do processo já instalada.

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
`cloud-backup` e `device-transfer`. **E o aplicativo não entra no backup na
nuvem:** `android:allowBackup="false"` (alerta 8 do CodeQL, 29/09/2026), porque
nada do que ele grava é preferência a restaurar. No Android 12 ou superior esse
atributo desliga o backup no Google Drive, mas não a transferência entre
aparelhos (página de mudanças de comportamento do Android 12), e por isso as
regras do domínio `device-transfer` continuam valendo.

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

O diário da sessão (`events_json` no web) vira a tabela `eventos`: o web o
guardava serializado e o lia com leitura estrita, tratando jornal corrompido
como falha explícita; com uma linha por evento não há o que corromper, e a
disciplina de falhar fechado passa para a custódia, abaixo.

**Decisões de esquema da terceira entrega (25–26/09/2026, primeira das duas
pull requests; plano com uma rodada de revisão cruzada, e o desenho de
armazenamento revisto inteiro depois de duas rodadas do Codex, com nova
rodada de revisão cruzada e seis decisões do operador em 26/09/2026):**

- **O D1 não entra no Room.** O web guarda o jornal como JSON numa coluna, a
  custódia circular como outro JSON validado por treze verificações, e
  recupera o texto aceito reparseando o markdown do artefato — porque o D1
  não tem transação, chave estrangeira nem tabela barata por evento. A
  primeira versão desta entrega portou essa forma para dentro do Room e
  herdou as fissuras dela (oito achados do Codex, cinco deles sintomas dessas
  três escolhas). O que vale é o comportamento do web; o armazenamento é o
  do Room:
  - **uma transição, um `UPDATE` condicional.** Não existe `@Update` de
    sessão. Cada transição (`criar`, `cancelar`, `retomar`, `reivindicar`,
    `substituirConteudo`, `subirPiso`, `tocar`, `gravarCustodia`,
    `concluir`) é um `UPDATE` das suas colunas com o portão de status na
    própria instrução (`WHERE id = ? AND status IN (…)`), que devolve quantas
    linhas mudou — zero é o CAS perdido. O portão não pode ser esquecido por
    quem chama. A troca de conteúdo preserva as colunas omitidas dentro do
    SQL (`COALESCE`), condicionada ao status lido;
  - **o jornal é a tabela `eventos`**, uma linha por evento, gravada na
    mesma transação da transição que o motivou (e cada evento carimba
    `atualizadaEm`, como o `appendEvent` do web). Não há blob para
    corromper, nem leitura estrita e tolerante do mesmo texto;
  - **o texto aceito é a coluna `textoAceito` do artefato**, canônico —
    aparado como o `trim` do JavaScript, `\r\n` como `\n`, e NUL recusado,
    não apagado — e o mesmo texto vai para `sessoes.textoAtual`; a retomada
    compara as duas colunas por igualdade. O markdown do web
    (`buildArtifactMarkdown`, byte a byte) é renderizado da linha na hora
    de exibir ou exportar, e não é gravado: gravá-lo duplicaria texto e
    relatório, e uma linha acima de 2 MiB não cabe no `CursorWindow` do
    Android; a linha do artefato (texto, relatório, auditoria) é recusada
    acima de 1 MiB (decisão do operador de 26/09/2026). Não há hash: a
    igualdade de duas colunas é mais forte do que o hash de uma delas;
  - **a custódia circular são colunas tipadas de `sessoes`** (artefato de
    custódia e anterior, rodada, índice do turno, turno do artefato, escala,
    agentes válidos e aprovações estáveis), sem chave estrangeira para
    `artefatos`, que já aponta para `sessoes`; a retomada confere o que ainda
    tem significado, cada verificação com a mensagem do web: artefato de
    custódia existente nesta sessão e aceito (`ready`/`not_ready`), do autor
    da linha; artefato anterior existente; turnos coerentes; contadores
    válidos; escala sem repetição e só com chaves conhecidas; aprovações
    dentro da escala; texto igual. Falha é `paused_resume_state_invalid` com
    o evento bloqueado, como no web. As verificações de forma de JSON
    (versão de esquema, `run_id`, JSON inválido) deixaram de ter objeto. A
    reconstrução legada do web não existe: não há dado legado no Android;
  - **cerca de execução.** `preparar` reivindica a sessão numa transação —
    uma linha em `execucoes` e `sessoes.execucaoAtual`, sob o portão de
    status —, e toda escrita do worker exige `execucaoAtual = :minha`. Um
    checkpoint tardio de uma execução superada falha por si só, mesmo com o
    status ainda `running`. A janela entre a última escrita guardada e a
    chamada paga ao provedor é a do web (releitura imediatamente antes da
    chamada, custo registrado pelo piso incondicional);
  - **o checkpoint por turno é uma transação**: insere o artefato, grava a
    custódia inteira com o status resultante sob o portão e a cerca, e
    insere o evento; zero linhas desfaz tudo, artefato incluído. A reserva
    do turno órfão na retomada fica (`max` dos turnos gravados), e a inserção
    de artefato é interna ao checkpoint;
  - **dinheiro em inteiros de 10⁻⁸ USD** nas colunas (a escala interna do
    protocolo), `BigDecimal` na API; o custo observado é a **soma** de cada
    chamada paga — `SET custo = custo + :delta`, atômico, saturando no máximo
    da coluna, sem portão e nunca dentro da transação do checkpoint, para que
    um checkpoint desfeito não apague gasto incorrido. Uma versão anterior
    desta seção prescrevia o piso `MAX(custo, :novo)` do web sobre o total
    local de cada execução; o Codex mostrou na #70 que duas execuções que se
    cruzam (o operador cancela e retoma enquanto a chamada antiga termina)
    partem do mesmo total e o `MAX` perde uma chamada paga. A soma não perde.
- **Nada se corta em silêncio.** Um artefato cujo markdown passaria do teto
  de 500 000 pontos de código do web é recusado no *checkpoint*, não
  truncado, e o teto é medido na mesma renderização que o leitor recebe
  (`bytesDoConteudo` é o tamanho dela); um relatório de revisão acima dos
  120 000 pontos de código do web é recusado, não serrado no meio do JSON;
  o texto aceito tem teto de 512 KiB (metade da linha do artefato) onde
  quer que seja gravado — artefato, checkpoint, texto final — e a linha de
  `eventos` tem teto de 1 MiB, para a linha da sessão, com o protocolo de
  até 640 KB e o pedido de até 160 KB ao lado, ficar abaixo dos 2 MiB do
  `CursorWindow` (decisão do operador de 27/09/2026);
  **toda saída de `queued`/`running`** — pausa no checkpoint, conclusão,
  cancelamento pelo operador, reconciliação (`interrupted`), reivindicação
  por outra execução (`superseded`) — fecha a linha da execução na mesma
  transação, para que o orçamento de 24 horas nunca conte como viva uma
  execução abandonada; o checkpoint recusa um artefato de outra sessão; as
  listas da custódia são lidas estritamente e os contadores de turno são
  normalizados com aritmética checada, para que uma linha corrompida falhe
  fechado em vez de ser regravada limpa; uma tarifa que não cabe na coluna
  de dinheiro é recusada ao salvar e inválida ao ler; um registro de
  evidência de outra versão de esquema não volta; os armazéns em arquivo
  recusam rodar dentro de uma transação de quem chama, porque só reclamam
  arquivos depois de um commit; e a gravação das configurações lê, valida e
  grava numa transação só. Da rodada do Codex publicada depois da mesclagem
  da #67: a reconciliação escreve só contra a execução que inspecionou (um
  worker substituto que reivindicou a sessão nesse meio-tempo fica em paz);
  um custo observado acima da coluna de dinheiro satura em vez de lançar
  depois da chamada paga; um texto aceito vazio é recusado no checkpoint, e
  um autor gravado sem texto é custódia inválida, não sessão nova a redigir
  de novo; um registro de link cujo `link_id` difere da chave da linha é
  corrupção, não registro.
- **Oito tabelas** mais `eventos`: as do D1 (sessão, artefato,
  configurações) e as que o desktop guarda em arquivos (registros de link e o
  diário deles, registros de evidência, anexos), mais as execuções do worker,
  que a tela soma na janela de 24 horas do `dataSync` (as que a tocam, não
  só as que começaram dentro dela). O esquema é exportado pelo plugin do Room
  para `core/sessao/schemas/`.
- **Colunas que não vêm.** `configured_secrets_json`: a chave é do cofre e só
  existe em tempo de execução, com o terceiro estado "não foi possível
  verificar agora". `models_json` fica gravado por sessão, para o histórico
  dizer com que modelo ela correu, mas não há seleção: o modelo de cada
  provedor é o mais novo, fixo no `:core:provedores`. `max_cycles` é
  validado e gravado como no web, mas o runner do web nunca o lê: o limite
  real é `roundTurnCount * 4` turnos seriais (`paused_cycle_limit`), e aqui é
  o mesmo. Minutos são inteiros. Um limite de minutos negativo é recusado,
  em vez de limpar o teto como o web faz.
- **Corpos de evidência e anexos são arquivos, não colunas.** Um corpo chega a
  8 MiB e um anexo a 16 MiB (o `MAX_OPERATOR_ARTIFACT_BYTES` canônico), e o
  `CursorWindow` do Android não lê uma linha acima de 2 MiB. Cada arquivo é
  uma geração imutável `<id>-<sha256>` sob `noBackupFilesDir` (temporário,
  `fsync`, `rename` atômico); a linha aponta para a geração, a anterior só
  some depois do *commit* da transação, e a limpeza de órfãos corre sob a
  trava do armazém. Uma recoleta sem corpo mantém a geração atual.
- **`pausada_aguardando_autenticacao` entra no ciclo de vida** como o décimo
  quarto estado retomável, ao lado dos treze do web — `error` incluído, que é
  o que a varredura do web e a reconciliação daqui gravam, e continua
  retomável. A reconciliação na abertura do aplicativo marca como `error` a
  sessão ainda ativa cujo *worker* não está vivo e pede a retomada com o
  líder e o painel da própria linha, sem o usuário redigitar nada — salvo
  a exceção da decisão 16, abaixo. `converged` é o fim: terminal, com
  `textoFinal` gravado, não retomável.
- **A deliberação (PR 3b) é o `runSession` do web sobre estas transações**,
  variável local por variável local, com os nove pontos de cancelamento
  cooperativo (a linha relida diz `running` e ainda aponta para esta
  execução, e o worker não pediu para parar) e as mensagens do web no jornal.
  O que difere, declarado:
  - **decisão 16 do operador (27/09/2026): chamada paga sem resultado não é
    retomada sozinha.** `execucoes` tem `chamadaEmVoo` e `chamadaIniciadaEm`
    (esquema v2): gravados numa atualização cercada pela execução
    imediatamente antes de cada despacho pago — rascunho ou revisor — e
    apagados na transação que registra o desfecho (*checkpoint*, artefato
    bloqueado, evento de pane, tentativa de rascunho falhada, pausa). Uma
    execução ainda aberta com o marcador morreu durante ou logo depois da
    chamada, e ninguém sabe se o provedor cobrou: `preparar` não a reivindica
    — põe a sessão em `error` com o evento "Chamada paga a X sem resultado
    registrado" — e a reconciliação não a reenfileira. O operador retoma pela
    tela e paga de novo por decisão dele, como o web exige depois de uma
    queda. A emenda A8 do plano da 3a fica estreitada a "retomada automática
    só quando nenhuma chamada paga estava em voo". O pedido de retomada
    (`retomar`) zera `execucaoAtual`, para um *checkpoint* tardio da execução
    morta não passar na cerca entre o pedido e a reivindicação seguinte;
  - **a ordem dos portões no topo de cada iteração é a do desktop**
    (`session_orchestration.rs:970-984`): releitura, evidência do operador
    (`falhaDeEvidenciaDoOperador`, que pausa `paused_final_audit` antes de
    qualquer revisor pago), convergência, teto de turnos seriais, escolha do
    revisor. Quando a evidência do operador e a convergência coincidem, a
    pausa por evidência vence. O web não tem esse portão;
  - **a auditoria do candidato é a de cinco estágios com o contexto de
    citações da sessão**: o hash do protocolo é o SHA-256 (64 hexadecimais)
    do texto do protocolo gravado na linha — o desktop usa o hash fixado
    pela interface ao importar o arquivo; aqui não há importação separada —,
    o manifesto é o anexo `citation-manifest` ou o vazio inicializado com
    esse hash, e o resumo dele vai no fim dos dois prompts, como o
    `evidence.block` do desktop; um anexo que se apresenta como manifesto e
    não pode ser lido pausa a sessão em `paused_final_audit` antes de
    qualquer chamada paga. O turno sem revisão usa a auditoria memorizada
    por texto dentro da execução (Plano D do web); a finalização a refaz do
    zero — "fresca" quer dizer nova execução da auditoria, não descarte do
    armazém de evidências, cuja validade canônica de 30 dias continua, no
    web e no desktop também. A tentativa corretiva leva o pacote do portão do
    texto atual (`## Current Deterministic Editorial Gate Packet`), como o
    desktop; o web não o tem;
  - **o revisor inescalável segue o web**: `paused_round_incomplete`
    ("No eligible reviewer could be scheduled before convergence."); o
    desktop audita e finaliza se a auditoria passar. Fonte da orquestração é
    o web (decisão 1 de 25/09/2026);
  - **não há hash de contexto de revisão** (`review_context_sha256` do
    desktop): o web, fonte da orquestração, não o tem, e os anexos não mudam
    com a sessão ativa;
  - **a estimativa de custo usa o teto de saída que a chamada realmente pede**
    (64 mil tokens, decisão de 23/09/2026), não os 20 mil do web; o teto vale
    sobre o acumulado da sessão inteira (emenda A13); a resposta
    `ExigeAutenticacao` do cofre pausa `pausada_aguardando_autenticacao`
    (emenda A1); `Incompleta` do provedor é falha operacional cobrada;
  - **a soma de custo é declaradamente sem cerca** (atômica): uma execução
    que pagou soma o seu custo mesmo depois de superada, e o guarda de custo
    de cada chamada compara o teto com o total **gravado**, relido na hora,
    não com um valor local que outra execução pode ter deixado velho. Todo o
    resto — evento, custódia, status, conclusão — carrega a cerca;
  - **quatro regras da rodada 1 do Codex na #70**, cada uma com teste e
    linha na matriz: o teto de tempo vale antes de cada tentativa corretiva
    (no desktop a tentativa volta ao topo do laço; o web perdeu a checagem ao
    aninhar); uma resposta `Incompleta` sem contagem de saída é cobrada como
    se tivesse gerado o teto de saída inteiro (pode ser uma geração parada
    nos 64 mil tokens; cobrar zero deixaria passar chamadas além do teto);
    os contadores de tentativa corretiva são semeados, na retomada, dos
    artefatos bloqueados da rodada sobre o texto atual (um worker parado no
    meio das tentativas não ganha três novas ao voltar); e a soma de custo
    acima.
- **O bloco `## Link Audit` do markdown** leva as linhas
  `link_integrity_audit.v1` da auditoria do porte, não o `LinkAuditResult`
  legado, e `Invalid links` conta os tons `error` e `blocked` — a regra
  `falhas` do motor. Taxas, modelos e agentes ativos continuam lidos com
  tolerância, como o `parseJson` do web; os registros de link e de evidência
  recusam número fracionário em campo inteiro.

**O que a PR 4a acrescenta ao esquema e aos repositórios (28/09/2026).**
O esquema vai à versão 3, por `AutoMigration(2, 3)`: `configuracoes` ganha
`emailDeContato`, o contato opcional para o Crossref (seção 5.4), gravado só
se obedecer à regra do `AgenteDeColeta` — ASCII visível, sem espaço, com um
`@`, até 254 caracteres —, porque um valor que o agente recusasse quebraria
toda auditoria dali em diante; e o agente de coleta é montado a cada auditoria
e a cada busca com o valor gravado naquele momento. O pedido de retomada
(`Retomada.pedir`) aceita um teto financeiro novo: o teto vale sobre o
acumulado da sessão inteira, e uma sessão pausada por custo pausaria de novo
na primeira chamada se fosse retomada com o mesmo teto. O teto novo tem de
passar do atual **e** do custo já observado, com o portão também no SQL, e
sobe na mesma transação da retomada, com o evento de cada uma: uma retomada
recusada, ou que perde a corrida no banco, deixa o teto onde estava.
`observarTodas` dá à tela inicial
a lista observada, no lugar da fotografia de `listar`, na ordem e no corte
do `GET /sessions` do web (`ORDER BY updated_at DESC LIMIT 30`): a sessão
tocada por último vem primeiro, e é dela que saem os cartões da tela inicial.
`observarResumos` dá aos autos da tela da sessão a lista dos artefatos sem o
texto aceito e o relatório, que chegam a 1 MiB cada, observada pela tabela de
artefatos: o custo e o jornal, que mudam a cada passo da sessão, não relêem a
lista, e o corpo só é lido para o artefato escolhido quando um artefato entra
ou a escolha muda.

### 4.3 Morte de processo é o novo timeout de Worker

No web, sessão travada é resolvida por um *cron* que varre sessões velhas. Sem
servidor, o gatilho muda: **na abertura do aplicativo, toda sessão em `running`
sem `WorkInfo` vivo é reconciliada.** O WorkManager é a fonte da verdade sobre
"existe execução viva"; o Room é a fonte da verdade sobre "onde a deliberação
parou".

A retomada já existe no web e porta inteira (309 linhas de estado circular):
ela reconstrói o ponto da rodada a partir dos artefatos aceitos e do jornal. O
que muda é apenas quem a dispara.

`Reconciliacao.naAbertura()` (PR 3b): para cada sessão em `queued`/`running`
sem trabalho vivo, marca como interrompida — a execução que ela tinha é
fechada na mesma transação, cercada pela execução inspecionada, com o motivo
da última parada que o WorkManager registrou no rótulo — e, se nenhuma chamada
paga estava em voo, pede a retomada e reenfileira (`pedir` e `enfileirar` só
depois de a escrita ter passado: um cancelamento ou uma pausa que chegue entre
a leitura e a escrita não é desfeito). Uma execução morta com o marcador da
chamada paga fica em `error` com a mensagem que diz por quê, e só o operador a
retoma (decisão 16, seção 4.2). Um pedido de retomada recusado (chave que
sumiu, tarifa zerada) vira evento na sessão, que fica retomável à mão. No fim,
os arquivos órfãos de evidências e anexos são limpos.

A reconciliação que falha por armazenamento (decisão 25 estendida, #80) não
derruba o aplicativo: vira um aviso com o motivo na tela inicial, só enquanto
vale e uma vez por tentativa, e se repete na próxima entrada em primeiro
plano, que começa apagando a falha da anterior; o desfecho de cada tentativa
é gravado ainda sob a trava, para o da seguinte vir por cima. A falha dentro da
transição de uma sessão desfaz a transação: a sessão fica como estava, e a
reconciliação seguinte a trata; a que já passou a `error` fica retomável à
mão (decisão do operador de 30/09/2026). A falha do banco do próprio
WorkManager na inicialização chega pelo
`Configuration.Builder.setInitializationExceptionHandler`, em vez de ser
lançada: a biblioteca entrega ao gancho, numa `IllegalStateException`, a
falha que a impediu de iniciar o banco dela (no caminho principal depois de
três tentativas, na migração do caminho do banco sem tentar de novo, e, sem
causa, com o usuário ainda bloqueado). Quem decide é o classificador único:
a causa de armazenamento vai à tela inicial, com o motivo dela; o resto,
inclusive a falha sem causa, é relançado, como a biblioteca faria sem o
gancho (revisão da #81).

### 4.4 Exibição e exportação do texto final (decisão do operador de 25/09/2026)

Aqui o Android difere dos dois aplicativos internos, e a diferença só foi dita
em 25/09/2026. No `maestro-app` e no `admin-app/Maestro AI`, de uso exclusivo
do operador, o texto final é Markdown que o `admin-app/MainSite` interpreta.
O `maestro-android` é produto para usuários diversos: o texto final é
**exibido formatado na tela do aparelho** e **exportado em Markdown, TXT e
PDF**. Isso é requisito do `:app` e se cumpre só com peças oficiais, sem
layout próprio:

- **Exibição.** A `commonmark-java` — o mesmo parser que a auditoria da
  seção 2.2 usa, então o que a auditoria aprova é exatamente o que a tela
  mostra — gera HTML com o seu `HtmlRenderer`, e o `WebView` do Android exibe
  esse HTML com uma folha de estilo do aplicativo. O `WebView` roda com
  JavaScript desligado, sem acesso a arquivo nem a conteúdo, carregando só a
  string gerada localmente (`loadDataWithBaseURL` com base nula) — é
  componente de exibição de HTML produzido no aparelho, não o produto web da
  seção 1. O HTML que
  chega ao `WebView` é sempre o que o renderizador produziu a partir do
  Markdown: HTML cru no texto final é recusado pela auditoria (seção 2.2,
  `raw_html_in_final_text`), e por isso o modelo não tem como injetar
  marcação nem script no que o aparelho renderiza.
- **PDF.** O print framework do Android: `PrintManager.print` com o
  `PrintDocumentAdapter` que o próprio `WebView` fornece. A paginação, a
  caixa de diálogo e o "Salvar como PDF" são do sistema.
- **Markdown.** O artefato, tal qual.
- **TXT.** Texto puro, pelo `TextContentRenderer` da `commonmark-java`:
  títulos e ênfases viram texto plano legível, sem marcas, e as listas mantêm
  o hífen (`-`) que o renderizador oficial escreve como marcador.

Nenhuma biblioteca de Markdown de terceiro (Markwon ou similar) nem gerador
de PDF próprio. Os três exportáveis saem do mesmo artefato que a auditoria
aprovou, e a exportação só é oferecida para texto liberado.

#### O que a PR 4b implementa disto (29/09/2026)

- **Liberado** é a sessão convergida com texto final não vazio; fora disso a
  tela diz que o texto ainda não foi liberado, e a tela da sessão nem oferece o
  botão.
- **O `WebView`**, além do que está acima: sem acesso a rede nem a imagens
  (`blockNetworkLoads`, `blockNetworkImage`), sem cache (`LOAD_NO_CACHE`), sem
  armazenamento DOM, sem nenhuma navegação (`shouldOverrideUrlLoading` recusa
  tudo), e com `onRenderProcessGone` tratado, para a morte do processo de
  renderização não derrubar o aplicativo; é destruído quando sai da tela. O
  HTML sai do `HtmlRenderer` com `escapeHtml(true)` e `sanitizeUrls(true)`, e a
  página leva uma política de conteúdo (`default-src 'none'`) que não deixa
  carregar nada de fora nem rodar script.
- **PDF** só depois de a página terminar de carregar (`onPageFinished`),
  pedido pela `Activity` na linha principal.
- **Markdown e TXT** pelo `CreateDocument` do seletor de documentos, gravados
  pelo `ContentResolver` fora da linha principal; o seletor cancelado não grava
  nada, e o documento de uma gravação que falhou é apagado. Recriada a
  `Activity` com o seletor aberto, o resultado chega a um ViewModel que ainda
  não leu a sessão: a exportação espera essa leitura, e sem texto liberado o
  documento criado também é apagado (achado do Codex na #78).

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

#### O lado de rede, decidido em 25/09/2026

A segunda entrega da MAEANDR-18 implementa no `:core:provedores` as interfaces
do `:core:protocolo`, com componentes oficiais e sem parser próprio, por
decisão do operador de 25/09/2026, depois de uma rodada de revisão cruzada
sobre o plano:

1. **Parser de URL: o `HttpUrl` do OkHttp.** Lê `http` e `https` como um
   navegador. Um host que é IP literal — IPv4 com 1 a 4 partes dentro dos
   limites do `inet_aton`, ou IPv6 entre colchetes — é julgado pelo texto e
   nunca vai a resolvedor; uma sequência numérica fora dos limites
   (`999.999.999.999`) é nome, e como nome não resolve, falha fechada.
   Senha vazia (`https://:@host`) é credencial, e é recusada.
2. **DNS: só o DNS sobre HTTPS do Google (`dns.google`)**, pelo módulo
   oficial `okhttp-dnsoverhttps`, com arranque fixo em 8.8.8.8 e 8.8.4.4 e
   **sem recaída para o DNS da rede**: numa rede que bloqueia o `dns.google`
   todo link falha como erro de DNS, e isso é aceito. O resolvedor tem duas
   faces: a conferência prévia da `RedePublica` vê todos os endereços, e
   por isso um nome que resolve para faixa privada é recusado com o motivo
   canônico antes de qualquer conexão; a conexão em si falha fechada, e
   recusa inteira uma resposta que misture público e privado — o caso do
   *rebinding*.
3. **`robots.txt`: o crawler-commons 1.6**, parser de referência da RFC 9309,
   no lugar do `robots_disallows_path` escrito à mão. Os códigos de status
   são os do canônico (401 e 403 proíbem; 404 e 410 liberam; outro erro é
   indisponível e a coleta segue), a comparação é só do caminho, e o nome
   do robô é o do canônico, `maestroeditorialai`, com ou sem versão, para
   que uma regra escrita para o crawler do desktop valha aqui. Duas leituras
   diferem do canônico e ficam travadas por teste: um grupo dirigido a este
   robô **substitui** o grupo `*`, em vez de somar-se a ele (RFC 9309, seção
   2.2.1), e `Crawl-delay` é ignorado, como no canônico — o teto do parser,
   que proibiria tudo quando excedido, fica desligado.
4. **Só `https://` é coletado.** O Android não envia texto claro por padrão,
   e liberar `http://` para a auditoria liberaria o aplicativo inteiro. O
   link em texto claro fica bloqueado com a nota que diz isso.
5. **A coleta é a de `execute_public_request`**, com um cliente novo e
   guardado — sem proxy, sem cookies, sem autenticador, sem interceptador,
   sem redirecionamento automático, só TLS moderno —, cinco saltos no máximo,
   cada um validado de novo, cabeçalhos presos à origem que nunca a
   atravessam, teto de 8 MiB conferido no `Content-Length` e na leitura, e a
   classificação de interação do canônico. Um `304` **não** é
   redirecionamento: o canônico testa `is_redirection()` (300–399) antes do
   `304`, e como um `304` não traz `Location`, o ramo do `304` dele nunca é
   alcançado — furo do canônico, registrado na MAESTRO-34.
6. **A gravação é uma interface** (`ArmazemDeEvidencias`), do `:core:sessao`.
   Com ela, registro pronto e fresco é reaproveitado sem requisição, a
   revalidação envia os validadores guardados — só quando a origem final da
   evidência é a origem requisitada: `ETag` de um destino em outra origem
   nunca viaja ao host original — e um `304` renova o registro
   — com a URL final da resposta que o renovou —, e `created_at` é
   preservado. Só o registro pronto carrega corpo, e **só o registro pronto
   guardado pode ser revalidado ou renovado por `304`**: o que falhou ou
   parou numa interação guarda os cabeçalhos, mas não é conteúdo, e um
   `304` sobre ele é o erro do canônico. Uma recoleta que falhou ou esbarrou
   numa interação nunca sobrescreve os bytes do último registro pronto.
7. **Identidade e contato.** O `User-Agent` é o do canônico,
   `MaestroEditorialAI/<versão> (Android; +<repositório>)`, em todo salto.
   O aplicativo pode guardar um **e-mail de contato opcional, do usuário**,
   que vai **só ao Crossref**, no parâmetro `mailto` e na variante polida do
   `User-Agent`, como a documentação do Crossref pede; o OpenAlex não usa
   e-mail. Nada da LCV Ideas & Software identifica o usuário em requisição
   nenhuma.
8. **A coleta é serial**, como no canônico, e bloqueante: o `:core:sessao` a
   roda em `Dispatchers.IO` e chama `cancelarTudo()` ao cancelar, porque a
   chamada bloqueante não vê o cancelamento da corrotina. O cancelamento é
   uma regra só, no transporte: depois de `cancelarTudo()` nada mais começa
   — nem um salto, nem a página depois do `robots.txt`, nem uma validação
   que consultaria o DNS; e a flag é conferida de novo depois de uma
   validação, que pode ter esperado uma consulta de nome —, e a coleta
   sobe como `ColetaCancelada`, que
   não é falha registrada: a auditoria cancelada para, em vez de seguir
   link a link. Um coletor cancelado não volta; o `:core:sessao` cria um
   por auditoria. O resolvedor DoH é do aplicativo e serve a mais de uma
   auditoria ao mesmo tempo; por isso **não é cancelado** (decisão do
   operador de 25/09/2026): uma consulta já em voo termina pelo próprio
   prazo de 10 s, e esse é o custo aceito.
9. **O registro nunca guarda credencial.** A URL do registro bloqueado pela
   validação é gravada sem usuário e senha e com o valor de toda chave
   sensível trocado por `<redacted>`; o canônico grava a URL bruta
   (furo 15 da MAESTRO-34). E uma recoleta que falhou ou parou numa
   interação nunca apaga do armazém o último corpo pronto: o registro
   novo é gravado com o corpo que já estava lá.

Não portados, por decisão do operador: a sondagem legada de 15 s do web e os
conectores de busca configuráveis (seção 11). Os hashes e os ids não são
comparáveis aos do desktop: o Jackson e o `serde` ordenam chaves de modo
diferente, e o `HttpUrl` serializa diferente da crate `url`.

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
  usuário roda uma sessão com aquele provedor ativo ou pede o teste das chaves
  (redação acrescentada pelo operador em 28/09/2026, quando o "Testar chaves"
  do web foi portado);
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
e portanto em todo aparelho que o `minSdk` 36 alcança. A documentação é
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

**O valor da janela é 300 minutos** (decisão 17 do operador, 28/09/2026): o
mesmo teto de tempo de uma sessão (`TETO_DE_MINUTOS`), uma constante de
produto amarrada à outra. Uma sessão sem limite de tempo que passar da janela
pausa em `pausada_aguardando_autenticacao` e volta com um toque. A tela pede a
autenticação antes de **toda** partida, retomada e "Testar chaves", com o
`BiometricPrompt` da plataforma (biometria forte ou a credencial do aparelho,
sem botão negativo, que a credencial proíbe), e nada começa quando ela falha:
o cofre não distingue uma janela vencida sem decifrar, e pausar logo depois de
começar é pior do que pedir antes.

A v1 assume `minSdk` 36, pela decisão do operador de 04/10/2026 para todos os
aplicativos \*-android, que superou a de 19/09/2026 (Android 14) herdada da
calculadora. Isso torna as duas APIs acima universalmente disponíveis e dispensa
caminho por nível de API — o que sobra é o hardware de StrongBox, que é questão
de aparelho, não de versão do sistema.

A decisão de 04/10/2026 nasceu de duas ações recomendadas do Play Console para
a calculadora-android 1.0.3: a exibição de ponta a ponta pode não estar
disponível para todos os usuários, e a calculadora usa APIs ou parâmetros
descontinuados para essa exibição (`Window.setStatusBarColor`,
`Window.setNavigationBarColor` e `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`).
Nem o código próprio da calculadora nem o deste aplicativo chama essas APIs;
os dois chamam o `enableEdgeToEdge()` do AndroidX.

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
- **O acumulado é o da sessão inteira** — o `custoObservadoUsd` da linha —,
  e não o da execução corrente. O web zera a base a cada retomada
  (`sessions.ts:3387-3389`), e uma sessão retomada pode gastar o teto de
  novo. Decisão do operador de 25/09/2026, sobre a revisão cruzada do plano
  da terceira entrega: uma sessão retomada nunca passa do teto configurado.
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
  real. O lado de rede da auditoria de links (seção 5.4) corre no mesmo
  módulo, contra um servidor falso em **HTTPS** — certificado de mentira do
  `okhttp-tls`, porque só `https://` é coletado — e um DNS de tabela: a
  cadeia de redirecionamentos e os cabeçalhos presos à origem, os tetos de
  corpo e de `robots.txt` na fronteira exata, o nome do robô com e sem
  versão, o nome que resolve para rede privada sem abrir socket, o
  *rebinding*, o armazém com reaproveitamento e `304`, e o motor do
  `:core:protocolo` com o parser e a coleta reais (`IntegridadeComRedeTest`).
  Nenhum teste toca a rede.
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
  adiante. Esse caso está na PR 3b (`DeliberacaoTest`), com o *worker*, e com
  ele os casos da deliberação, cada um sobre Room em arquivo, com o chamador e
  a auditoria substituídos por dublês combinados: a sessão nova que redige,
  revisa e converge (jornal, artefatos, execução fechada, marcador limpo);
  a revisão que transfere a custódia e exige nova rodada; a chamada paga sem
  resultado que não é retomada sozinha e a retomada manual que segue do turno
  certo (decisão 16); o marcador que sai com o desfecho de cada chamada; o
  cancelamento pelo operador com a chamada em voo, que não grava artefato nem
  evento; a parada pelo worker; três panes seguidas; tentativas corretivas
  esgotadas com os artefatos bloqueados; `READY` sobre texto que falha na
  auditoria recusado sem tentativa; a auditoria final fresca que pausa a
  convergência; a evidência do operador antes do revisor pago e vencendo a
  convergência na retomada; o teto de turnos seriais; rascunho vazio e
  todos os agentes falhando; a janela de autenticação vencida; o erro
  inesperado que vira `error`; o cancelar-e-retomar no meio de uma chamada
  que não deixa a execução antiga escrever. Com o `work-testing` oficial
  (`TrabalhoEReconciliacaoTest`): o serviço em primeiro plano sobe antes da
  chamada; pausa e erro também são `Result.success()`; a parada pelo sistema
  é rotulada sob a cerca e a segunda execução não paga de novo; a
  reconciliação reenfileira só quem não tinha chamada em voo, não desfaz um
  cancelamento que chegou entre a leitura e a escrita, não reenfileira um
  pedido recusado, e o agendador mantém um trabalho por sessão. Na JVM: o
  escalonador do web (`EscalonamentoTest`, os casos de `sessions.test.ts`),
  o orçamento de seis horas e as citações da sessão. A
  primeira pull request (26/09/2026) provou a metade que já existia, no mesmo
  emulador do `:core:seguranca`, em toda pull request: `preparar` reivindica a sessão
  (execução e cerca) e uma sessão cancelada ou reconciliada não é
  reivindicada; o *checkpoint* grava artefato, custódia e evento juntos, um
  portão perdido não deixa nem o artefato, e um checkpoint de execução
  superada falha na cerca; o banco é fechado, cada objeto descartado e tudo
  reaberto sobre o mesmo arquivo, e a preparação da retomada devolve a
  rodada, o turno, as aprovações e a custódia exatas, com o status `running`
  e o evento de retomada no fim do jornal; a custódia adulterada no arquivo
  (contador, texto) e o texto sem custódia caem em
  `paused_resume_state_invalid` com as mensagens do web; cada transição tem o
  caso em que o portão recusa; a soma de custo é atômica, satura e não tem
  portão; a troca de conteúdo preserva as colunas omitidas e só escreve no
  status lido; o pedido de retomada com painel e líder inválidos; o artefato
  órfão além do contador; os `Flow` da sessão e dos eventos; os registros de
  link atravessados pelo motor real; um corpo de 8 MiB e um anexo de 16 MiB
  em arquivo, e o byte a mais recusado. Na JVM ficam as funções puras: o
  markdown derivado do artefato, uma entrada que falha por verificação da
  custódia, o texto canônico, o corte de 2 s do tempo, o saneamento de taxas
  e agentes, o `resolveStartRequest`, o dinheiro em inteiros e o teto no
  limite exato sobre o acumulado da sessão.
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
  memória, sem Hilt e sem rede. Na PR 4a, sobre o Room real num arquivo
  temporário, com o cofre, o agendador e a autenticação substituídos por
  dublês: as quatro validações do início e as duas da retomada, com as
  mensagens do web; o caminho permissão → autenticação → gravação →
  enfileiramento, e nada gravado nem enfileirado quando a autenticação falha;
  a lista observada e as chaves relidas na volta à tela; o aviso do orçamento
  com cinco horas e meia gastas, e a ausência dele com uma; as métricas, a
  janela dos últimos oito eventos, os autos e as cinco abas; o artefato novo
  que entra nos autos sem mudança na sessão; o custo
  acumulado que acompanha o banco sem evento novo; o cancelamento gravado
  **antes** de o trabalho ser cancelado; o teto novo recusado abaixo do gasto
  e aceito acima, com a autenticação antes, e o teto que fica onde estava
  quando a retomada é recusada; a chave guardada na segunda
  tentativa depois da autenticação; o aparelho sem trava; o terceiro estado do
  cofre; o limite de 301 minutos recusado sem gravar; o teste de chaves que só
  chama quem tem chave. E o arranque do processo: a fábrica instalada, a
  configuração do WorkManager que é a do `Application`, e a sessão "rodando"
  sem trabalho vivo reconciliada na entrada em primeiro plano. Na JVM: os
  rótulos, os formatos, o diff, o limiar do aviso e o manifesto.
  Na PR 4b, na mesma montagem, com o seletor de documentos pela API oficial
  (`ActivityResultRegistry`) e um navegador dublê que só anota a URL — nenhum
  teste abre navegador: o texto final liberado numa página travada com as três
  exportações, o cliente da página que avisa o fim, recusa navegar e troca a
  página que morreu, a página que sai quando o texto perde a liberação, a
  sessão que não convergiu, o Markdown tal qual e o TXT do renderizador, o
  seletor cancelado ou o documento que não abre, que não deixam arquivo, a
  gravação que falha, que pede a remoção do documento, e a exportação que
  chega a um ViewModel recém-criado, que espera a leitura da sessão ou, sem
  texto liberado, pede a remoção; o manifesto lido, o
  recusado, o acima do teto e o que não muda com a sessão em execução, nem
  quando chega a um ViewModel que ainda não leu o status, o do
  formulário gravado antes do enfileiramento, o escolhido durante um início
  em curso, que fica para a próxima sessão, o do formulário lido de novo
  contra o protocolo trocado depois da escolha, e o disco que falha, que avisa
  sem derrubar o aplicativo e sem deixar sessão na fila; os links do texto atual, e não
  os de uma versão anterior, relidos quando o texto muda com a tela aberta e
  quando a auditoria grava as linhas do mesmo texto, a releitura que falha e
  mantém a lista, a nota e a decisão de um link que saiu da lista ou voltou
  com outro hash, que não vão para outro conteúdo, e o que se digita durante
  a revisão e a importação, que fica no formulário; a
  revisão e as propostas desligadas com a sessão em execução, com a captura
  liberada, e recusadas na linha dividida com outra sessão do mesmo texto em
  execução; a decisão e as propostas cujo diário não grava, que não ficam na
  linha; a passagem ao navegador só com a URL que a regra de rede aceitou,
  a recusada que não chega ao navegador, o navegador que não abre e o disparo
  barrado por política; o arquivo importado sob o link, sem mudá-lo, o de
  tipo fora da lista e o escolhido para um link que saiu da lista; o disco que
  falha na importação, ao consultar a evidência guardada e ao gravar, e na busca, que avisa sem derrubar o aplicativo; a revisão com a recusa
  do motor para nota curta e para aceite de link que não passou; as propostas
  com o provedor escolhido, que sobrevivem à auditoria seguinte na linha
  decidida, a busca que não se monta por erro de disco, e a busca cancelada quando a tela sai, que não grava as propostas
  nem quando a resposta já tinha chegado; a aba Links dos autos que
  leva à tela; e, pela decisão 25, o banco cheio em cada ação de tela que
  grava — iniciar, cancelar, retomar, salvar as configurações, anexar,
  remover, a passagem ao navegador, a importação, a decisão e as propostas —,
  que avisa sem derrubar o aplicativo e sem gravar pela metade; o erro de
  disco nas leituras que preparam uma ação (abrir a retomada, escolher o
  manifesto, testar as chaves); a releitura que falha depois de cancelar e de
  retomar, que não deixa nada gravado; o motivo do cofre que não grava ou não
  remove; o aviso da exportação, que só diz "nada foi salvo" com o documento
  apagado; e a passagem cujo resultado não foi anotado, com o navegador
  aberto ou não. Pela decisão 25 estendida (#80): em cada tela, a leitura que
  falha ao abrir, com o aviso, o motivo no lugar e a releitura na volta da
  tela; sem releitura antes da volta, nas telas inicial e da sessão e em
  Licenças; a que falha depois de lida, que mantém o que a tela mostrava, nas
  telas inicial e da sessão; um aviso por volta, nas telas inicial e da
  sessão e na abertura dos Anexos; o aviso reaberto por um toque (o artefato
  dos autos) ou por uma ação (Anexos e Links); os cartões da tela inicial,
  que dizem "Não lido"; os eventos da sessão que volta ao topo, que não são
  os de outra; a parada de uma execução anterior, que não volta; a
  exportação do texto final sem a sessão lida; a reconciliação da abertura
  que falha, que espera a trava e se repete na entrada seguinte; o banco do
  WorkManager que não abre, pelo gancho instalado de fato; o cancelamento
  pela notificação que não grava e a notificação que ele posta, ao lado da
  do serviço, e não no lugar dela; e o classificador com as classes reais do
  SQLite (`ArmazenamentoNoAparelhoTest`). Pelas decisões 26 e 27 (#82): a
  leitura e a exportação do documento que o provedor nega, com o motivo, e
  o arquivo escolhido que não se lê, também com o motivo (`DocumentosTest` e
  o texto final), em cada tela que lê o documento
  escolhido (Anexos, a captura dos Links e o manifesto da tela inicial),
  sobre um provedor real que nega acesso (o de contatos do sistema, cujas
  permissões o aplicativo não declara); o erro que não é do documento, que
  segue adiante na leitura e na exportação, sem apagar nada (um provedor de
  teste que lança, pela API oficial `ContentResolver.wrap`); e o cancelamento
  pela notificação recusado, que avisa e não cancela o trabalho. O banco
  cheio é o `SQLiteFullException` do framework, lançado sob demanda por um
  `openHelperFactory` de teste do Room (`BancoCheio`), no ponto em que o
  SQLite o lançaria: um gatilho SQL daria `SQLITE_CONSTRAINT`, e o limite de
  páginas só falha quando a escrita pede página nova. Pela MAEANDR-31, na
  `MainActivity` de verdade, porque o `adjustResize` do manifesto vale só nela:
  com o teclado na tela aberto num campo baixo das configurações, a barra
  superior não sai do lugar e fica inteira abaixo da barra de status, o campo em
  foco fica inteiro à vista acima do teclado, o conteúdo termina no topo do
  teclado e os avisos aparecem acima dele (`TecladoTest`). Os testes de tela
  rodam sem o teclado do sistema, que o `Cenario` intercepta
  (`InterceptPlatformTextInput`): o texto entra pela ação semântica do campo, e
  o toque seguinte não corre contra a animação do teclado. Na JVM: o
  renderizador do texto final, o tipo da captura, os
  rótulos do painel do desktop, a regra do manifesto do formulário, que não
  deixa começar durante a leitura dele, e a ordem das releituras das telas de
  anexos e de links (`OrdemDasLeituras`); na tela de anexos, o manifesto de
  outro protocolo recusado com o hash ativo e a releitura antiga que termina
  depois de outra já aplicada, que não repõe a lista velha; as leituras da tela
  (`LeiturasDaTelaTest`), a verificação da abertura e a falha do WorkManager
  (`ReconciliacaoDaAberturaTest`) e o classificador (`ArmazenamentoTest`, no `:core:sessao`), com a entrada do documento escolhido
  (`motivoDoDocumento`) e o `SecurityException` que segue adiante fora dela (decisão 26). No `:core:provedores`, na JVM, a
  captura assistida (as regras de nome, tipo, tamanho e bytes mágicos, a ordem
  das recusas, os dois registros) e a busca que guarda cada resultado,
  devolve a falha do armazém, como o `save_stored(...)?` do canônico, e,
  cancelada depois da resposta, não grava o resultado seguinte nem entrega
  nada ao motor; no
  `:core:protocolo`, a entrada do diário gravada dentro da transação da linha
  na auditoria, na decisão e nas propostas, as quatro regras de vínculo do manifesto na auditoria e
  fora dela; no `:core:sessao`, na JVM, o manifesto desvinculado recusado
  antes do rascunho, e, instrumentado, a sessão do formulário que nasce com o
  manifesto ou não nasce, os anexos travados na transação com a sessão na
  fila ou em execução, as linhas de link da sessão e a ordem delas,
  os registros de evidência de um endereço, a decisão 23, o teto de tempo
  conferido de novo depois da auditoria do portão e o HTML cru que a auditoria
  real recusa antes de virar texto final.

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
  mais; o aplicativo não entra no backup na nuvem, e o banco local e o segredo
  cifrado ficam **fora também da transferência entre aparelhos** (seção 4.2);
- as consultas de nome da auditoria de links vão ao **DNS sobre HTTPS do
  Google** (`dns.google`), não ao DNS da rede em que o aparelho está — decisão
  do operador de 25/09/2026 (seção 5.4). É a única parte do aplicativo que fala
  com o Google sem o usuário ter ativado o Gemini, e leva só o nome do host
  citado no texto;
- o **e-mail de contato é opcional e do usuário**: se ele o preencher, vai só
  ao Crossref, na busca de evidências, como a documentação do Crossref pede;
  nenhum e-mail ou identificador da LCV vai em requisição nenhuma.

A publicação segue a esteira já em paridade (seção 3), com notas de versão em
`play/release-notes/pt-BR.txt` e o teto de 500 caracteres por idioma verificado
antes do build.

**Dois canais de distribuição** (decisão do operador de 25/09/2026, comum aos
três aplicativos Android da LCV Ideas & Software): na Play Store o aplicativo
custa, inicialmente, R$ 10,00, como uma espécie de taxa de conveniência — o
valor pode mudar por decisão do operador e nada no código depende dele; no
GitHub Release do repositório o mesmo aplicativo, com o APK assinado pela
mesma esteira, é gratuito. Registro na Discussion #62.

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
   o resto do desenho. **Estado em 27/09/2026:** a PR 3b entregou o rótulo
   (`getStopReason()` no jornal, sob a cerca) e o caminho da segunda execução
   depois de uma parada (`preparar` com a decisão 16); a medição em aparelho
   de verdade continua pendente, e a tela do `:app` (MAEANDR-21) é quem a
   torna possível. **Estado em 28/09/2026:** a PR 4a entregou as telas de
   sessão e de configurações; a medição pode ser feita num aparelho com
   chaves de verdade.
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
   revista sem custo. **O teto de sessão foi fixado em 300 minutos em
   25/09/2026** (seção 4.1), revisável sem recifrar nada. **O valor da
   janela foi fixado pelo operador em 28/09/2026: 300 minutos** (seção 6.2);
   fica aberta só a medição, em sessões reais, de que o par se sustenta.

Uma pendência de medição **saiu daqui por estar documentada, não por ter sido
medida**: o teto do `dataSync`. Uma versão anterior afirmava que a documentação
não o declarava e mandava medir em aparelho. Declara, na página de mudanças de
comportamento do Android 15, e agora está na seção 4.1. Ausência de um fato em
duas páginas não é ausência do fato.

### Aberta, porte do canônico

- **A revisão de link confere também a URL final e a cadeia de
  redirecionamentos.** No canônico atual (`maestro-app` `0e17817`,
  MAESTRO-34), a revisão só vale se a linha ainda tiver a URL normalizada, o
  hash, a URL final e a cadeia de redirecionamentos lidos; o motor do Android,
  portado de `68528f9`, confere os dois primeiros. Medido ao portar a tela de
  links (seção 2.2) e registrado na
  [#77](https://github.com/LCV-Ideas-Software/maestro-android/issues/77)
  (MAEANDR-26); o porte mede tudo o que o MAESTRO-34 mudou no motor, não só a
  revisão.

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
