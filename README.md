# Parcial — Acortador de enlaces hexagonal

**React + TypeScript / Java 21 + Spring Boot 3.5.6 / MongoDB + Redis / WebSocket / Abstract Factory.**

Proyecto de referencia que sigue el boceto compartido: dos botones laterales («Acortar enlace» y «Ver enlaces»), formulario, tabla con ID/enlace original/enlace acortado, contador Redis de 30 segundos por WebSocket, rutas de redirección funcionales. No contiene enlaces precargados: se crean desde la pantalla.

## Estructura del backend

```text
backend/src/main/java/com/example/demo/
├── app/
│   ├── App.java                         <- Arranque / scan / scheduling
│   └── InfrastructureConfig.java        <- Spring inyecta puertos a través de Abstract Factory
├── domain/model/
│   └── ShortLink.java                   <- Modelo puro
├── application/
│   ├── ports/
│   │   ├── LinkRepository.java          <- Contrato de Mongo
│   │   ├── LinkCache.java               <- Contrato de caché
│   │   └── LinkInfrastructureFactory.java <- Abstract Factory
│   └── service/
│       ├── LinkService.java             <- Lógica de negocio
│       ├── LinkListResult.java
│       └── CacheStatus.java
└── infrastructure/
    ├── factory/
    │   ├── MongoRedisFactory.java       <- Implementación principal: familia Mongo + Redis
    │   └── MongoOnlyFactory.java        <- Familia alternativa: Mongo + sin caché
    ├── persistence/mongo/
    │   ├── MongoLinkRepository.java     <- Adaptador MongoDB
    │   ├── MongoLinkDocument.java
    │   └── SequenceDocument.java        <- Generador numérico de IDs
    ├── cache/
    │   ├── redis/RedisLinkCache.java     <- Adaptador Redis, TTL no renovable
    │   └── noop/NoCacheLinkCache.java
    └── web/
        ├── controllers/
        │   ├── LinkController.java
        │   ├── RedirectController.java
        │   └── ApiExceptionHandler.java
        ├── dto/
        │   ├── CreateLinkRequest.java
        │   ├── LinkResponse.java
        │   └── ListLinksResponse.java
        └── websocket/
            ├── CacheStatusSocket.java    <- Mensaje de estado cada segundo
            └── WebSocketConfig.java
```

### Configuración inicial en Windows

Requisitos: Java 21, Maven 3.9+, Node.js 20.19+ o 22.12+, Docker Desktop en ejecución.

**Terminal 1 (raíz del proyecto):**

```powershell
docker compose up -d
docker compose ps
```

**Terminal 2 (backend):**

```powershell
cd backend
mvn spring-boot:run
```

**Terminal 3 (frontend):**

```powershell
cd frontend
npm install
npm run dev
```

Abrir http://localhost:5173. API en http://localhost:8080. Si ya tienes MongoDB o Redis funcionando localmente, puedes utilizarlos en lugar de Docker, o cambiar los puertos del `docker-compose.yml` y las variables `MONGODB_URI`, `REDIS_HOST`, `REDIS_PORT`.

> El proyecto entregado no incluye el binario Maven Wrapper (`mvnw`); usa Maven instalado, o copia `.mvn/wrapper`, `mvnw` y `mvnw.cmd` desde un proyecto Spring existente con wrapper compatible. `npm install` genera el lockfile.

## Flujo que se evalúa

1. Pulsar «Acortar enlace». Se resalta esta pestaña; muestra el formulario.
2. Pegar `https://www.youtube.com/` y pulsar «Acortar». La URL se valida y se guarda en MongoDB con ID numérico y código aleatorio.
3. Abrir el enlace `http://localhost:8080/r/XXXXXXXX`: el backend busca el código en MongoDB y devuelve HTTP `302` a la URL original.
4. La creación realmente nueva registra `cacheWindowConsumed=true` en MongoDB y crea `shortener:link:{id}` en Redis con `SET NX` y TTL de 30 segundos. Cada ID tiene su propia ventana.
5. «Ver enlaces» consulta el catálogo persistente y las entradas individuales vigentes. La tabla conserva ID/original/acortado y añade «Estado de caché»; cada fila activa tiene fondo verde, etiqueta «Redis activo» y segundos restantes.
6. WebSocket `/ws/cache` envía un array de `{ "id": 1, "active": true, "expired": false, "remainingMillis": 24000, "observedAtMillis": 1790000000000 }` al conectar y cada segundo, leyendo PTTL de Redis.
7. Cambiar de sección, consultar, reconectar o recargar recupera el TTL real sin escribir claves ni renovarlas. React no decrementa el contador mediante un temporizador local; `observedAtMillis` ordena las respuestas HTTP/WebSocket para descartar estados atrasados.
8. Al expirar, desaparece el verde y aparece «Expirado». Consultar, redirigir o volver a acortar la misma URL reutiliza el registro sin iniciar otra ventana.
9. Otra URL realmente nueva inicia su propio contador y no altera ningún enlace anterior.

### API

| Método | Ruta | Descripción |
| --- | --- | --- |
| `POST` | `/api/links` | JSON `{ "url": "https://example.com" }`: crea o reutiliza un enlace y devuelve ID/original/acortado |
| `GET` | `/api/links` | Objeto con `links` y `cacheStatuses` por ID |
| `GET` | `/r/{code}` | Redirección `302` a la URL original (enlace funcional) |
| WebSocket | `/ws/cache` | Array de estados por ID con TTL real, incluidas expiraciones |

### Comprobar el TTL en Redis, sin tocarlo

```powershell
docker exec -it parcial_acortador_redis redis-cli
PTTL shortener:link:1
GET shortener:link:1
```

`PTTL` da los milisegundos restantes. Si muestra `-2`, la llave ya no existe; `-1` significaría que no tiene caducidad, cosa que el programa **no** debería dejar en este caché. Importante: `PTTL` y `GET` no renuevan la expiración.

### Consultar MongoDB

```powershell
docker exec -it parcial_acortador_mongodb mongosh acortador
show collections
db.links.find().sort({_id: 1})
```

### Duplicidad y compatibilidad

`UrlNormalizer` elimina espacios exteriores, convierte esquema/host a minúsculas, elimina puertos HTTP/HTTPS predeterminados y equipara la ruta vacía con `/`. Conserva mayúsculas de la ruta, barra final no vacía, escapes, orden de parámetros, fragmentos y puertos no predeterminados. `http` y `https` siguen siendo URLs distintas. La URL original guardada conserva su forma para la redirección.

El índice único y disperso de `normalizedUrl` decide la concurrencia en MongoDB; solamente el ganador de la inserción escribe Redis. La marca persistente se inserta antes de intentar el caché. Si el proceso falla entre MongoDB y Redis, el enlace queda guardado y no se reintenta la ventana: se prioriza impedir ciclos adicionales. No existe una transacción distribuida entre ambos servicios.

Al iniciar, la migración asigna la identidad normalizada a los documentos antiguos. Si ya había duplicados, el menor ID es el registro reutilizable y los demás mantienen su ID, código y redirección; no se borra ningún documento. La migración registra su avance y es repetible. Los enlaces antiguos sin marca se muestran «Sin caché activo» y nunca abren ventanas por lectura. El generador numérico se ajusta al máximo ID existente. Las antiguas claves globales se ignoran y caducan por su TTL original.

### Ejecutar pruebas

Pruebas unitarias y compilación:

```powershell
cd backend
mvn clean test
cd ../frontend
npm ci
npm run build
```

Las pruebas de integración son opcionales por defecto y se habilitan explícitamente para usar **servicios de prueba aislados**. No apuntarlas a la base real:

```powershell
# Desde la raíz; crear una sola vez.
docker run -d --name shortener_fix_test_mongo -p 127.0.0.1:27018:27017 mongo:7
docker run -d --name shortener_fix_test_redis -p 127.0.0.1:6380:6379 redis:7-alpine
cd backend
$env:SHORTENER_INTEGRATION_TESTS='true'
mvn clean test
mvn package -DskipTests
```

Las pruebas usan bases MongoDB nuevas y conservan sus documentos. Comprueban expiración real de 30 segundos, lectura a los 10 segundos, concurrencia, migración sin pérdida de datos, redirecciones y reconstrucción del servicio.

Prueba integral con navegador (requiere el JAR anterior, Docker y el puerto 8080 libre; detener antes el backend de desarrollo):

```powershell
cd frontend
npx playwright install chromium
npm run test:e2e
```

El test inicia Spring Boot contra los contenedores de prueba, usa/reutiliza Vite en 5173, verifica navegación y recarga de React, contadores independientes, expiración por WebSocket, duplicidad, redirección 302 y reinicios reales de Spring Boot/MongoDB. Al terminar detiene su backend. Las capturas quedan en `frontend/test-results/`. Puede usarse un Chromium instalado mediante `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH`. Detener los contenedores de prueba al terminar con `docker stop shortener_fix_test_mongo shortener_fix_test_redis`; no es necesario borrar datos.

### Descripción para defender el examen

- **Arquitectura hexagonal:** `LinkService` únicamente conoce `LinkRepository` y `LinkCache`. No importa clases de Mongo/Redis ni detalles web.
- **Abstract Factory:** `LinkInfrastructureFactory` produce una familia coherente de dos adaptadores. `MongoRedisFactory` devuelve un repositorio Mongo y caché Redis. `MongoOnlyFactory` usa el mismo repositorio con `NoCacheLinkCache` (perfil Spring `sin-cache`; únicamente como demostración del patrón). Al arrancar, Spring crea las implementaciones y las inyecta en `LinkService`. No se fabrica ninguna implementación durante cada petición.
- **MongoDB:** colección `links`; `_id` numérico autogenerado sobre `sequences`; índices únicos en `shortCode` y `normalizedUrl` (disperso), con marca persistente `cacheWindowConsumed`.
- **Redis:** claves individuales `shortener:link:{id}` con una única ventana de 30 segundos al crear. Ninguna lectura, redirección o reconexión escribe `SET`/`EXPIRE`. MongoDB decide si la URL ya existe, incluso después de perder o expirar Redis.
- **WebSocket:** no mide el tiempo por su cuenta; obtiene los milisegundos del propio Redis y comunica el valor al navegador cada segundo.

### Notas técnicas

- La URL pública generada se configura con `PUBLIC_BASE_URL`; por defecto usa `http://localhost:8080`. Si abres la app desde otro dispositivo, ajusta esa variable a la dirección alcanzable del backend.
- El desarrollo frontend emplea proxy Vite (`/api`, `/r`, `/ws`) y por ello evita problemas habituales de CORS.
- El perfil `sin-cache` es demostrativo, **no cumple** la exigencia de usar Redis; no activarlo durante la evaluación.

### Referencias técnicas

- [Índices únicos y dispersos de MongoDB](https://www.mongodb.com/docs/v8.0/core/index-sparse/): permiten conservar documentos históricos sin identidad normalizada y proteger la identidad de los registros canónicos.
- [SET de Redis](https://redis.io/docs/latest/commands/set/): `NX` con expiración crea la entrada solamente si está ausente; la autorización histórica para crearla la decide MongoDB.
