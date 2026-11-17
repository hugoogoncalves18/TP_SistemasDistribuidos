# Leilões em tempo real — Nível COMPLETO

Sistemas Distribuídos (LEI/LSIRC) 2026/2027 · **Nível declarado: Completo** (B1–B7, I1–I4, C1–C2)

> **Todos os comandos abaixo funcionam em PowerShell, cmd, Linux e macOS.** As opções `-D...` e os
> endereços vão entre aspas de propósito: no PowerShell, `-Djava.net.preferIPv4Stack=true` sem aspas
> é partido no primeiro ponto e dá `Could not find or load main class .net.preferIPv4Stack=true`.

---

## 1. Compilar (Java 17+, só biblioteca padrão)

```
javac --release 17 -encoding UTF-8 *.java
```

Compila tudo: servidor, clientes, consola e testes. Não são precisas bibliotecas nem IDE.

---

## 2. Servidor

```
java "-Djava.net.preferIPv4Stack=true" Servidor [porto] [historico] [grupo] [portoUdp] [portoRmi] [antiSniping]
```

| Argumento | Por omissão | Para quê |
|---|---|---|
| `porto` | `5050` | porto TCP dos clientes |
| `historico` | `historico.log` | ficheiro de histórico (criado se não existir) |
| `grupo` | `239.255.0.1` | grupo multicast dos avisos |
| `portoUdp` | `6060` | porto UDP dos avisos (cada grupo de alunos usa o seu) |
| `portoRmi` | `5051` | registo RMI da consola de administração (≠ `porto`) |
| `antiSniping` | `30` | N segundos da janela final (`0` desliga) |

Os argumentos são **posicionais**: para mudar o último tens de indicar os anteriores.

```
# Arranque normal (tudo por omissão)
java "-Djava.net.preferIPv4Stack=true" Servidor

# Tudo explícito
java "-Djava.net.preferIPv4Stack=true" Servidor 5050 historico.log "239.255.0.1" 6060 5051 30

# Demonstração: anti-sniping de 5 s (para usar com leilões curtos)
java "-Djava.net.preferIPv4Stack=true" Servidor 5050 historico.log "239.255.0.1" 6060 5051 5

# Sem anti-sniping
java "-Djava.net.preferIPv4Stack=true" Servidor 5050 historico.log "239.255.0.1" 6060 5051 0

# Outro porto TCP e outro histórico (por exemplo, um segundo servidor de teste)
java "-Djava.net.preferIPv4Stack=true" Servidor 5060 outro.log "239.255.0.1" 6061 5061 30
```

Parar: `Ctrl+C` (fecha socket, relógio, RMI, multicast e ficheiro).

Opções avançadas (opcionais, vão **antes** do nome da classe):

| Opção | Para quê |
|---|---|
| `"-Dfsync=false"` | não força o disco a cada gravação (mais rápido; só para testes de carga) |
| `"-Dmulticast.interface=eth0"` | forçar a interface do multicast (nome ou IP). Usar igual no servidor **e** nos clientes |
| `"-Djava.rmi.server.hostname=192.168.1.10"` | administrar a partir de **outra máquina** (IP do servidor). Por omissão é `localhost` |

---

## 3. Cliente de consola

```
java "-Djava.net.preferIPv4Stack=true" Cliente [host] [porto] [grupo] [portoUdp] [descartarN]
```

| Argumento | Por omissão | Para quê |
|---|---|---|
| `host` | `localhost` | máquina do servidor |
| `porto` | `5050` | porto TCP |
| `grupo` | `239.255.0.1` | grupo multicast |
| `portoUdp` | `6060` | porto UDP (igual ao do servidor) |
| `descartarN` | `0` | `N > 0`: **descarta 1 de cada N avisos** (simula perdas) |

```
# Cliente normal
java "-Djava.net.preferIPv4Stack=true" Cliente

# Ligar a outra máquina
java "-Djava.net.preferIPv4Stack=true" Cliente 192.168.1.10 5050

# Simular perdas: descarta 1 em cada 2 avisos
java "-Djava.net.preferIPv4Stack=true" Cliente localhost 5050 "239.255.0.1" 6060 2

# Simular perdas: descarta 1 em cada 3 avisos
java "-Djava.net.preferIPv4Stack=true" Cliente localhost 5050 "239.255.0.1" 6060 3
```

### 3.1 Comandos do protocolo (Anexo A) — escrever no cliente

Um pedido por linha, **em maiúsculas**, campos separados por **um só espaço**.

| Comando | Sessão | Exemplo | Resposta |
|---|---|---|---|
| `REGISTAR <utilizador> <palavra-passe>` | não | `REGISTAR ana segredo1` | `OK` (não inicia sessão) |
| `LOGIN <utilizador> <palavra-passe>` | só sem sessão | `LOGIN ana segredo1` | `OK` |
| `CRIAR <base> <incremento> <duração-s> <artigo>` | exige | `CRIAR 100 5 300 Relógio de bolso` | `OK id=1` |
| `LISTAR` | não | `LISTAR` | `OK n` + n linhas `LEILAO <registo>` |
| `CONSULTAR <id>` | não | `CONSULTAR 1` | `OK id=1 estado=ABERTO melhor=... restante=287 artigo=...` |
| `LICITAR <id> <valor>` | exige | `LICITAR 1 105` | `OK n=1` |
| `SAIR` | não | `SAIR` | `OK` e o servidor fecha a ligação |
| `ESTADO <id>` *(extensão nossa)* | não | `ESTADO 1` | `OK seq=<k> id=... ` (o `CONSULTAR` com o nº de sequência) |

Limites: utilizador 3–20 caracteres `A-Z a-z 0-9 _`; palavra-passe 1–64 sem espaços; base e valor 0–999 999 999;
incremento 1–999 999 999; duração 1–86 400 s; artigo 1–60 caracteres.

Erros: `SINTAXE`, `VALOR`, `SEM_SESSAO`, `JA_AUTENTICADO`, `CREDENCIAIS`, `EXISTE`, `INEXISTENTE`, `FECHADO`, `PROPRIO`, `BAIXA`, `INTERNO`.

Sessão completa de exemplo (dois clientes):

```
# cliente 1 (ana)                         # cliente 2 (rui)
REGISTAR ana segredo1                     REGISTAR rui segredo2
LOGIN ana segredo1                        LOGIN rui segredo2
CRIAR 100 5 300 Relógio de bolso          LICITAR 1 100
LISTAR                                    LICITAR 1 102      -> ERRO BAIXA
CONSULTAR 1                               LICITAR 1 105      -> OK n=2
SAIR                                      SAIR
```

### 3.2 Comandos locais do cliente (começam por `/`, não vão ao servidor)

| Comando | Efeito |
|---|---|
| `/seguir <id>` | acompanha o leilão: mostra os avisos multicast em tempo real e recupera perdas |
| `/parar <id>` | deixa de acompanhar o leilão |
| `/ajuda` | mostra a ajuda |

Sair do cliente: `SAIR`, ou `Ctrl+D` (Linux/macOS) / `Ctrl+Z` e `Enter` (Windows).

---

## 4. Consola de administração (RMI, processo separado)

```
java ConsolaAdmin [host] [portoRmi]
```

| Argumento | Por omissão |
|---|---|
| `host` | `localhost` |
| `portoRmi` | `5051` |

```
java ConsolaAdmin
java ConsolaAdmin localhost 5051
java ConsolaAdmin 192.168.1.10 5051
```

| Comando | Efeito |
|---|---|
| `leiloes` | lista todos os leilões (um registo por linha) |
| `utilizadores` | lista os utilizadores com sessão iniciada agora |
| `cancelar <id>` | cancela um leilão **aberto**: fecha sem vencedor, fica gravado e é anunciado por multicast |
| `ajuda` | mostra a ajuda |

Erros do `cancelar`: `ERRO INEXISTENTE` (não existe) e `ERRO FECHADO` (já fechou ou já foi cancelado).
Se o servidor cair e voltar, a consola volta a ligar-se sozinha no comando seguinte.

---

## 5. Testes

```
# Conformidade do protocolo (G2) — ficheiro do Moodle, servidor a correr
javac --release 17 -encoding UTF-8 Conformidade.java
java Conformidade localhost 5050

# Concorrência de licitações (B5/G1): clientes x valores
java TesteConcorrencia localhost 5050 20 100

# Atomicidade do cancelamento vs. licitações simultâneas (C1/G1): host, portoTcp, portoRmi, licitantes
java TesteCancelamento localhost 5050 5051 10
```

Cada um termina com `PASSA` (código 0) ou `FALHA` (código 1).

---

## 6. Cenários de demonstração (um por requisito)

Preparação comum: 4 terminais — **S** servidor, **A** `ana` (cria), **R** `rui` (licita), **O** observador.
Em **A** e **R**: `java "-Djava.net.preferIPv4Stack=true" Cliente`, depois `REGISTAR` + `LOGIN`
(`ana`/`segredo1`, `rui`/`segredo2`).

| Cenário | O que fazer | O que se vê |
|---|---|---|
| **B1** vários clientes | abrir 3 clientes ao mesmo tempo | todos respondem; no servidor, `[pool-1-thread-N]` diferentes |
| **B2** protocolo | `java Conformidade localhost 5050` | `9/9` e `21/21`, `PASSA` |
| **B3** palavra-passe | `REGISTAR` e abrir `historico.log` | só aparece o SHA-256, nunca a palavra-passe |
| **B5** concorrência | `java TesteConcorrencia localhost 5050 20 100` | `PASSA` |
| **B6** leilão fechado | **A**: `CRIAR 1 1 3 Teste`; esperar 4 s; **R**: `LICITAR <id> 1` | `ERRO FECHADO` |
| **B7** reinício | licitar; `Ctrl+C` em **S**; arrancar de novo; `CONSULTAR <id>` | estado reposto |
| **B7** linha truncada | parar **S**; acrescentar uma linha **sem `\n` final**: PowerShell `[IO.File]::AppendAllText("historico.log","LICIT 1 9 rui 11")`; Linux/macOS `printf 'LICIT 1 9 rui 11' >> historico.log` (não usar `echo`, que acrescenta `\n`); arrancar | `Aviso: linha final truncada ... descartada` e arranca |
| **I1/I2** avisos | **O**: `java "-Djava.net.preferIPv4Stack=true" Cliente` e `/seguir 1`; **R** licita | `[leilao 1 . n.1] nova licitacao: ...` em tempo real |
| **I2** aviso perdido | **O**: `java "-Djava.net.preferIPv4Stack=true" Cliente localhost 5050 "239.255.0.1" 6060 2` e `/seguir 1`; **R** faz 5–6 licitações | `[simulacao] aviso descartado`, `[!] Perda no leilao...`, `Estado atual (n.k)...` |
| **I3** fecho automático | **A**: `CRIAR 100 5 15 Relogio`; **O**: `/seguir <id>`; **R** licita; esperar 15 s | `FECHADO. Vencedor: rui com ...` (ou `Sem vencedor.`); `LICITAR` seguinte → `ERRO FECHADO` |
| **I4** fecho no arranque | **A**: `CRIAR 100 5 10 Teste`; `Ctrl+C` em **S**; esperar 12 s; arrancar **S** | `Arranque: 1 leilao(oes) vencido(s) fechado(s)` |
| **C1** listar / sessões | `java ConsolaAdmin`, depois `leiloes` e `utilizadores` | registos e nomes com sessão (desce quando alguém faz `SAIR`) |
| **C1** cancelar | **O**: `/seguir <id>`; consola: `cancelar <id>`; **R**: `LICITAR <id> ...` | **O**: `CANCELADO pelo administrador. Sem vencedor.`; **R**: `ERRO FECHADO`; `CONSULTAR` → `FECHADO`, `vencedor=-` |
| **C1** atómico | `java TesteCancelamento localhost 5050 5051 10` | `PASSA` |
| **C1** reinício | cancelar; `Ctrl+C` em **S**; arrancar; `CONSULTAR <id>` | continua `FECHADO` e `vencedor=-` |
| **C2** anti-sniping | **S** com N=5: `java "-Djava.net.preferIPv4Stack=true" Servidor 5050 historico.log "239.255.0.1" 6060 5051 5`; **A**: `CRIAR 100 5 10 Teste`; **O**: `/seguir <id>`; **R**: `LICITAR <id> 100` logo (fora da janela) e `LICITAR <id> 110` quando restarem ≤ 5 s | 1.ª sem adiar; 2.ª mostra `FECHO ADIADO: restam 5 s`; `CONSULTAR` com `restante` atualizado; fecha ≈ 2 s depois dos 10 s |

---

## 7. Ficheiros

| Ficheiro | Papel |
|---|---|
| `Servidor.java` | `main`, `accept()`, pool de threads, relógio de fecho (200 ms), arranque do RMI |
| `Atendedor.java` | uma por ligação TCP: valida o Anexo A (+ `ESTADO`), responde |
| `Estado.java` | estado partilhado, **região crítica**, histórico, fecho, cancelamento, anti-sniping, difusão |
| `Leilao.java` | dados de um leilão |
| `Multicast.java`, `Difusor.java` | grupo/interface e emissor dos avisos UDP |
| `LigacaoTcp.java`, `Acompanhamento.java`, `Cliente.java` | cliente (2 threads: teclado e avisos) |
| `AdminRemoto.java`, `AdminImpl.java`, `ServicoAdmin.java`, `ConsolaAdmin.java` | RMI: interface, implementação, arranque no servidor, consola |
| `ErroProtocolo.java` | `ERRO <CODIGO> <motivo>` |
| `TesteConcorrencia.java`, `TesteCancelamento.java` | testes de B5 e de C1 |

---

## 8. Extensões ao protocolo (para o relatório; não alteram o Anexo A)

**Aviso multicast** (UDP, texto UTF-8, `nome=valor` separados por espaço):

```
AVISO tipo=LICITACAO leilao=<id> seq=<n> valor=<v> utilizador=<quem> restante=<s> prolongado=SIM|NAO
AVISO tipo=FECHO     leilao=<id> seq=<n> vencedor=<quem ou -> melhor=<v>
AVISO tipo=CANCELADO leilao=<id> seq=<n>
```

`seq` é por leilão: 1, 2, 3… uma por licitação aceite (igual ao `n` do `OK n=`) e uma para o fecho **ou** para o
cancelamento (`licitacoes + 1`). Há **perda** quando chega um `seq` superior ao último visto + 1.
O **adiamento** (C2) vai no próprio aviso da licitação (`prolongado=SIM`, com o `restante` já atualizado), sem outro `seq`.

**Pedido de estado** (TCP): `ESTADO <id>` → `OK seq=<k> id=... estado=... artigo=...`. Erros: `SINTAXE`, `VALOR`, `INEXISTENTE`. Sem sessão.

**Recuperação no cliente:** `seq <= ultimo` ignora; `seq == ultimo+1` mostra; `seq > ultimo+1` → `ESTADO`, mostra o estado
atual e continua a partir do seq recebido (cada aviso aparece uma só vez). A cada 5 s confirma o `seq` por `ESTADO`,
porque a perda do **último** aviso não se deteta por avisos seguintes.

**Interface RMI** (`AdminRemoto`, objeto `"admin"`, registo no porto 5051 criado no próprio servidor):

| Operação | Resultado | Erros (`ErroProtocolo`) |
|---|---|---|
| `List<String> listarLeiloes()` | um registo (A.4) por leilão, por id | — |
| `List<String> utilizadoresComSessao()` | utilizadores com sessão agora | — |
| `void cancelar(int id)` | fecha o leilão aberto sem vencedor | `INEXISTENTE`, `FECHADO` |

---

## 9. Decisões (resumo para o relatório)

- **Concorrência:** um lock global (`synchronized` em todos os métodos públicos de `Estado`). `licitar`: valida → (anti-sniping) →
  grava → altera memória → difunde → devolve; só então sai o `OK`. «Aceite» = validada **e** gravada no histórico.
- **Cancelamento vs. licitação:** as threads TCP e as threads RMI chamam os **mesmos** métodos do `Estado`, no mesmo lock.
  Ganha quem entrar primeiro: licitação primeiro → `OK` e fica gravada (o leilão fecha sem vencedor); cancelamento primeiro →
  a licitação recebe `FECHADO`. Depois de a consola receber a confirmação, nenhuma licitação é aceite nesse leilão.
- **Tempo:** só o relógio do servidor decide. A thread `relogio` fecha os leilões vencidos de 200 ms em 200 ms (atraso máximo ≈ 0,2 s).
  `licitar` compara a hora, por isso rejeita com `FECHADO` mesmo antes de o fecho efetivo.
- **Anti-sniping:** licitação aceite com `fecho - agora <= N` adia o fecho para `agora + N`; fora da janela não adia nada; nunca antecipa.
  O fecho automático usa a hora nova.
- **Histórico** (texto, uma linha por evento, sempre com `\n`):
  `REG user sha256` · `LEILAO id base inc fechoMs criador artigo` · `LICIT id n user valor fechoMs` · `FECHO id seq` · `CANCEL id seq`.
  A hora de fecho em vigor vai **na mesma linha** da licitação (uma só escrita: não pode ficar a licitação sem o adiamento).
  Palavras-passe só em SHA-256. `fsync` a cada gravação. Uma linha final sem `\n` é descartada no arranque.
- **Falhas:** aviso perdido → `ESTADO`; cliente cai → só termina a sua thread (e a sua sessão sai da lista); servidor cai → o histórico
  repõe tudo e o `FECHO` em falta é feito no arranque; multicast indisponível → o servidor continua sem avisos; consola sem servidor → volta a ligar.
- **RMI:** o registo é criado no servidor (porto diferente do TCP); guardam-se referências fortes ao objeto e ao registo (senão o GC
  recolhe-os → `NoSuchObjectException`); `java.rmi.server.hostname=localhost` por omissão.

## 10. Limitações assumidas

- Lock global: licitações em leilões diferentes também se serializam.
- O fecho depende da hora do sistema do servidor (`currentTimeMillis`): se o relógio recuar, o fecho recua com ele.
- A janela do anti-sniping (N) é da configuração do servidor, não do histórico: se se reiniciar com outro N, os leilões em curso mantêm a hora de fecho gravada mas as próximas licitações usam o N novo.
- A consola RMI não tem autenticação (qualquer processo que alcance o porto 5051 pode cancelar leilões).
- SHA-256 sem sal (como o enunciado pede); histórico sem compactação.

## 11. Declaração de uso de IA

*(Preencher no relatório: onde e para quê foi usada. Cada elemento do grupo tem de saber explicar o código na defesa.)*
