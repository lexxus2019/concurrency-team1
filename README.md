# Аукцион: три сервиса (учебный проект по конкурентности)

Простой аукцион из трёх Spring Boot сервисов. Только бэкенд, данные хранятся в памяти (без БД).

```
   POST /api/seller/items          GET  /api/buyer/items
   DELETE /api/seller/items/{id}   POST /api/buyer/items/{id}/bids
        │                                  │
        ▼                                  ▼
┌───────────┐                       ┌───────────┐
│  seller   │─────── HTTP ─────────▶│   buyer   │
│  :8081    │                       │   :8082   │
└───────────┘                       └───────────┘
        │                                  │
        └──────────┬───────────────────────┘
                   ▼
            ┌─────────────┐
            │   auction   │  товары и ставки в HashMap в памяти процесса
            │   :8080     │
            └─────────────┘
```

- **auction** (`:8080`) — сам аукцион: хранит товары и ставки в памяти, принимает ставки, отдаёт списки.
- **seller** (`:8081`) — сервис продавца: выставляет товары на аукцион и снимает их с продажи.
- **buyer** (`:8082`) — сервис покупателя: смотрит список товаров и делает ставки.

Требования: JDK 21+, Maven.

## Запуск

Каждый сервис запускается отдельным процессом (три терминала):

```bash
mvn -pl auction spring-boot:run
mvn -pl seller  spring-boot:run
mvn -pl buyer   spring-boot:run
```

Сервисы seller и buyer обращаются к аукциону по адресу из `application.properties` (`auction.url`).

## API аукциона

| Метод  | URL                  | Тело запроса                          | Смысл                  |
|--------|----------------------|----------------------------------------|------------------------|
| POST   | `/api/items`         | `{"name":"Велосипед","startPrice":1000}` | выставить товар      |
| DELETE | `/api/items/{id}`    | —                                      | снять товар с продажи  |
| GET    | `/api/items`         | —                                      | список товаров         |
| GET    | `/api/items/{id}/bids` | —                                    | список ставок на товар |
| POST   | `/api/items/{id}/bids` | `{"bidder":"Иван","amount":1500}`    | сделать ставку         |

Правило ставки: новая ставка должна быть выше всех прежних (и выше стартовой цены).

## Пример проверки (Windows: `curl.exe`, в PowerShell `curl` — это не curl)

```bash
# продавец выставляет товар через свой сервис
curl.exe -X POST http://localhost:8081/api/seller/items \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"Велосипед\",\"startPrice\":1000}"

# покупатель видит товар
curl.exe http://localhost:8082/api/buyer/items

# покупатель делает ставку
curl.exe -X POST http://localhost:8082/api/buyer/items/1/bids \
  -H "Content-Type: application/json" \
  -d "{\"bidder\":\"Иван\",\"amount\":1500}"

# ставки видно на аукционе
curl.exe http://localhost:8080/api/items/1/bids
```

## Тесты

```bash
mvn -pl auction test
```

- **`RaceTest`** — тесты на гонки. В ветке main они **намеренно красные**: это учебный результат. Тест печатает конкретные числа (`=== RaceTest...: запросов 1500, уникальных id в ответах 1499, ...`) и делает читаемые assertions:
  - `sequentialRequestsWork` — зелёный всегда: последовательные запросы работают;
  - `concurrentBids` — красные ставки: теряются `add` в `ArrayList`, `nextBidId++` раздаёт одинаковые id;
  - `concurrentAddItems` — теряются `put` в `HashMap`, `nextItemId++` раздаёт одинаковые id.
  Цель следующих веток — починить аукцион так, чтобы `RaceTest` стал зелёным.
- **`LoadTest`** — зелёный всегда, печатает пропускную способность (шт/сек последовательно и в 100 потоков), чтобы видеть цену синхронизации в следующих ветках.

Так как `RaceTest` в main красный, для сборки jar без тестов используйте `mvn -DskipTests package`.

## Важно для курса

В версии 1 (`main`) сервис аукциона **намеренно не потокобезопасен**: `HashMap`, `ArrayList` и `nextItemId++` без синхронизации. Spring обрабатывает каждый HTTP-запрос в отдельном потоке, поэтому при параллельных запросах возможны потерянные товары, пропущенные id и «двойные» победители ставок. Каждое следующее улучшение будет делаться в отдельной ветке от `main`.

## Ветки со способами убрать гонки

Каждая ветка — один способ, минимальный диффом изменён только `auction`. Критерий: `RaceTest` зелёный (кроме ветки-ловушки 04), числа `LoadTest` в сообщении коммита.

| Ветка | Способ |
|-------|--------|
| `fix/01-synchronized-methods` | `synchronized` на всех методах |
| `fix/02-sync-blocks` | `synchronized`-блок на приватном lock-объекте |
| `fix/03-reentrant-lock` | явная `ReentrantLock` |
| `fix/04-atomics-chm` | `AtomicLong` + `ConcurrentHashMap` — ловушка: RaceTest остаётся красным |
| `fix/05-chm-compute` | атомарные `compute()` от ConcurrentHashMap |
| `fix/06-read-write-lock` | `ReentrantReadWriteLock` (читатели параллельны) |
| `fix/07-per-lot-lock` | лок на отдельный лот + агрегат Lot |
| `fix/08-cas-snapshot` | `AtomicReference` + неизменяемый снапшот (lock-free чтение) |
| `fix/09-actor-thread` | одно-поточный исполнитель (actor), локов нет вообще |
| `fix/10-embedded-artemis` | очередь сообщений (embedded Artemis) вместо блокировок |
