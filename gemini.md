# PLAN DE DESARROLLO Y GOBERNANZA ARQUITECTÓNICA - JUEGO DE CARTAS MVP

Este documento sirve como **instrucción maestra y registro de progreso** para el agente de desarrollo (Antigravity). El agente debe leer este archivo, ejecutar la fase indicada por el usuario, verificar que `mvn verify` sea exitoso y actualizar el estado de los checkboxes (`[ ]` a `[x]`).

---

## 1. Principios de Arquitectura y Gobernanza (Estrictos)

Para evitar que `mvn verify` o `ArchitectureFitnessTest` fallen, se deben respetar las siguientes decisiones arquitectónicas:

1. **RG-01 (Aislamiento de Capas):**
   * Los controladores (`app.gameplay.controller`) y la UI no pueden depender directamente de `infrastructure` o `persistence`.
   * Toda la interacción pasa exclusivamente a través de la interfaz `IGameController` (mediante `GameOrchestrator`).

2. **RG-02 (Independencia de Estrategias y Sin Ciclos):**
   * `app.rules.classic` y `app.rules.apuntado` son totalmente independientes y no se conocen entre sí.
   * Cero dependencias circulares entre los paquetes de primer nivel (`core`, `player`, `deck`, `gameplay`, `scoring`, `rules`).

3. **RG-03 (Topología Mediador):**
   * Los módulos colaterales (`player`, `deck`, `scoring`, `rules`) **NUNCA** se comunican punto a punto entre sí.
   * Toda comunicación inter-módulo la coordina el **Mediador** (`app.gameplay.orchestrator.GameOrchestrator`).
   * **Entidades compartidas:** `Card`, `Suit`, `Rank`, `CardBack`, `GameConfig` viven en `app.core` para que todos los módulos puedan usarlas sin violar RG-03.

4. **RG-04 (Complejidad Ciclomática $\le 10$):**
   * Ningún método en `app.rules..` ni `app.scoring.calculator..` puede superar $CC = 10$.
   * Utilizar descomposición en métodos pequeños y evaluar patrones Strategy o listas de evaluadores en lugar de estructuras `if/else` o `switch` anidados.

5. **Compositor Principal:** `app.Main` es la clase raíz responsable de instanciar la base de datos SQLite, los repositorios, los servicios y arrancar Javalin.

---

## 2. Ubicación de Archivos Estáticos e Imágenes (`cards`)

Tus imágenes ya están ubicadas correctamente en la siguiente ruta:

```text
src/
└── main/
    ├── java/                      # Código Java (app/core, app/deck, etc.)
    └── resources/
        └── static/
            └── assets/
                └── cards/         # RUTAS DE RECURSOS DE CARTAS
                    ├── cardback/  # backred/backblue (reversos impar/par)
                    ├── clubs/     # Cartas de Tréboles
                    ├── diamonds/  # Cartas de Diamantes
                    ├── hearts/    # Cartas de Corazones
                    ├── jokers/    # Jokers
                    └── spades/    # Cartas de Picas
```

---

## 3. Estado de Progreso del Proyecto

* [x] **Fase 1: Configuración, Dependencias y Mazo Base**
* [x] **Fase 2: Motor de Reglas (Escaleras, Espejos, Jokers) y Cálculo de Puntaje**
* [x] **Fase 3: Persistencia SQLite y Orquestador de Partida (Mediador)**
* [x] **Fase 4: Servidor Javalin, Controlador REST y WebSockets**
* [x] **Fase 5: Frontend Responsive (Móvil/PC), Tablero de 10 Casillas y Reconexión**
* [x] **Extensión: Modalidad Mixta (`MIXED`) con elección de cierre Regular (Clásica) o Apuntado**
* [x] **Suite Completa de Pruebas Automatizadas (Unitarias, Integración Multijugador, WebSockets E2E, SQLite ACID, ArchUnit y Checkstyle)**

---

## 4. Detalle de Fases de Desarrollo

### FASE 1: Configuración, Dependencias y Mazo Base

**Objetivo:** Actualizar `pom.xml` e implementar las clases fundamentales en `app.core` y `app.deck` respetando la independencia de módulos.

#### Tareas:
1. Actualizar `pom.xml` con las dependencias requeridas (Javalin 6.3.0, Jackson 2.17.2, SLF4J 2.0.16, SQLite JDBC 3.46.1.0).
2. Crear en `app.core`:
   * `Suit.java` (SPADES, HEARTS, DIAMONDS, CLUBS, JOKER).
   * `Rank.java` (ACE .. KING, JOKER).
   * `CardBack.java` (RED, BLUE).
   * `Card.java` (Record o clase inmutable con atributos `id`, `suit`, `rank`, `back`, `deckNumber` y helper `getImagePath()`).
   * `CardValuePolicy.java` (Enum con comportamiento para cálculo de valor de cartas).
   * `CyclePolicy.java` (Enum con comportamiento para evaluar secuencias cíclicas o lineales).
   * `EliminationLimit.java` (Enum con valores 51, 101, 151, 201, 251).
   * `GameMode.java` (Enum con modalidades `CLASSIC` [Regular], `APUNTADO` y `MIXED` [Mixto], incluyendo resolución de modalidad concreta de cierre).
   * `GameConfig.java` (Objeto mutable de configuración con validación de barajas según jugadores 2-16 — máximo 4 por baraja — y límite configurable de cierre en Apuntado).
3. Eliminar la clase antigua `app/deck/Card.java` si existía y refactorizar cualquier import hacia `app.core.Card`.
4. Crear en `app.deck`:
   * `app.deck.service.IDeckService.java`
   * `app.deck.service.DeckManager.java` (Soporta 1 a 4 barajas, Jokers opcionales, reverso impar rojo / par azul, reciclado automático de descarte conservando la última carta visible).
5. Crear pruebas unitarias en `tests/unit/deck/DeckManagerTest.java`.
6. Ejecutar `mvn verify` y asegurar que no hay violaciones de ArchUnit ni Checkstyle.

---

### FASE 2: Motor de Reglas y Cálculo de Puntaje ($CC \le 10$)

**Objetivo:** Implementar la lógica para validar ternas, cuartas, cierres (Clásica/Regular, Apuntado y Mixto) y penalizaciones.

#### Tareas:
1. En `app.rules.engine`:
   * `HandPartitioner.java`: Algoritmo de agrupación óptima para 10 cartas en mano (detecta la mejor combinación posible considerando escaleras, espejos y Jokers).
   * `RuleEngine.java`: Despacha dinámicamente a `CLASSIC` o `APUNTADO`, y en modo `MIXED` valida el cierre según la elección concreta del jugador (`CLASSIC`/`APUNTADO`) sin acoplar las estrategias entre sí (RG-02).
2. En `app.rules.classic`:
   * `ClassicRulesStrategy.java` que implementa `IGameRulesStrategy`.
   * Evaluadores independientes con $CC \le 10$: `FourJokersEvaluator` (-25 pts), `FullStraightEvaluator` (-25 pts), `SevenThreeEvaluator` (-20 pts), `FourSixEvaluator` (-15 pts), `FourThreeThreeEvaluator` (-10 pts).
3. En `app.rules.apuntado`:
   * `ApuntadoRulesStrategy.java` (cierre estricto `3-3-3` + 1 sobrante menor al límite configurable — por defecto `< 5` pts — sumando el valor de esa sobrante al puntaje de quien cierra).
4. En `app.scoring.calculator`:
   * `PenaltyCalculator.java` (Suma el puntaje de cartas sobrantes según `CardValuePolicy`, respetando $CC \le 10$).
5. En `app.scoring.service`:
   * `IScoreService.java` y `ScoreManager.java`.
6. Crear pruebas unitarias en `tests/unit/rules/` y `tests/unit/scoring/`.
7. Ejecutar `mvn verify`.

---

### FASE 3: Persistencia SQLite y Orquestador de Partida (Mediador)

**Objetivo:** Implementar la persistencia local ACID y la orquestación de la partida a través de `GameOrchestrator`.

#### Tareas:
1. Crear `src/main/resources/schema.sql` con la estructura SQLite:
   * Tablas: `Jugador`, `Partida`, `ParticipantePartida`, `Ronda`, `PuntajeRonda`.
2. En `app.player` y `app.scoring.infrastructure`:
   * Repositorios SQLite utilizando transacciones `BEGIN / COMMIT / ROLLBACK` para cierres de ronda.
3. En `app.gameplay.orchestrator`:
   * `IGameController.java` (Interfaz que expone todos los comandos del juego).
   * `GameOrchestrator.java` (Mediador que coordina `IDeckService`, `IRuleEngine`, `IScoreService` y repositorios sin que se conozcan entre sí).
   * Generación de código alfanumérico de 6 dígitos para la sala.
   * Lógica de turnos, estado de juego y soporte para reconexión mediante token de sesión.
4. Crear pruebas de integración en `tests/unit/gameplay/`.
5. Ejecutar `mvn verify`.

---

### FASE 4: Servidor Javalin y WebSockets

**Objetivo:** Exponer la API REST y la comunicación en tiempo real con WebSockets.

#### Tareas:
1. En `app.gameplay.controller`:
   * `RestLobbyController.java` (Creación de sala, unirse a sala, obtener configuraciones).
   * `WebSocketController` / `GameWebSocketHandler` (Eventos: `JOIN`, `RECONNECT`, `START_GAME`, `DRAW_DECK`, `DRAW_DISCARD`, `DISCARD`, `DECLARE_CLOSE` con campo `closeMode` para modo `MIXED`).
   * Filtrado de estado por jugador (`GAME_STATE` envía las 10 cartas reales solo al dueño; a los oponentes únicamente les envía la cantidad de cartas y el color de reverso).
2. En `app.Main`:
   * Instanciación de componentes (Composition Root).
   * Configuración e inicio del servidor Javalin en puerto `7070` sirviendo archivos estáticos desde `src/main/resources/static`.
3. Ejecutar `mvn verify`.

---

### FASE 5: Frontend Web Responsive (Móvil/PC)

**Objetivo:** Construir la interfaz de usuario ligera, intuitiva y adaptable.

#### Tareas:
1. Crear `src/main/resources/static/index.html`:
   * Pantalla de Lobby (Crear/Unirse con código de 6 dígitos y panel de opciones de sala).
   * Pantalla de Juego (Mesa de partida, mazo, pozo de descarte con última carta visible, vista de oponentes y modal de elección de cierre Regular vs. Apuntado en modo Mixto).
2. Crear `src/main/resources/static/css/styles.css`:
   * Layout adaptable con CSS Grid / Flexbox.
   * Componente de 10 casillas apilables para las cartas del jugador (permite ver el borde superior de cartas apiladas).
   * Animaciones ligeras y diseño apto para touch en celulares y click en PC.
3. Crear `src/main/resources/static/js/`:
   * `app.js`: Manejo de conexiones WebSocket, reconexión automática vía `localStorage` (guardando código de sala + `playerId`).
   * `board.js`: Lógica local de ordenamiento de cartas (drag & drop y toque carta->casilla).
4. Pruebas automatizadas E2E multijugador y WebSockets (`MultiplayerMatchEndToEndTest`, `WebSocketGameplayEndToEndTest`, `TransportIsolationTest`).
5. Ejecutar `mvn clean verify` final (`BUILD SUCCESS`, 87/87 pruebas en verde y 0 violaciones de Checkstyle).