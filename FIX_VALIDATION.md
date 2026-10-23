# Gestión individual del caché: cambios y validación

Validación ejecutada el 22 de octubre de 2026 (America/Bogota).

Cada URL nueva puede abrir una única ventana Redis de 30 segundos. La misma URL reutiliza su ID y código; las consultas, la navegación, las recargas y los reinicios no abren ventanas adicionales. Se conserva la arquitectura hexagonal y las dos familias de Abstract Factory.

## Archivos modificados

| Área | Archivos (relativos al proyecto) | Cambio |
| --- | --- | --- |
| Dominio | `backend/src/main/java/com/example/demo/domain/model/ShortLink.java`, `UrlNormalizer.java` (nuevo) | Marca histórica de ventana consumida y criterio conservador de identidad de URL. |
| Puertos | `backend/src/main/java/com/example/demo/application/ports/LinkRepository.java`, `LinkCache.java` | Creación atómica con resultado nuevo/existente; caché y PTTL por ID. |
| Servicio | `backend/src/main/java/com/example/demo/application/service/LinkService.java`, `CacheStatus.java`, `LinkListResult.java` | Sólo la creación ganadora inicia Redis; snapshots individuales con TTL y fecha de observación. La redirección sigue dependiendo de MongoDB. |
| Persistencia | `backend/src/main/java/com/example/demo/infrastructure/persistence/mongo/MongoLinkRepository.java`, `MongoLinkDocument.java` | Índice único disperso, migración repetible sin borrados, recuperación de la creación concurrente y secuencia compatible con IDs antiguos. |
| Caché | `backend/src/main/java/com/example/demo/infrastructure/cache/redis/RedisLinkCache.java`, `cache/noop/NoCacheLinkCache.java` | `shortener:link:{id}`, SET NX de 30 segundos sólo al crear, lecturas sin escrituras; adaptación de la familia sin caché. |
| HTTP y WebSocket | `backend/src/main/java/com/example/demo/infrastructure/web/controllers/LinkController.java`, `dto/ListLinksResponse.java`, `websocket/CacheStatusSocket.java` | API y socket por ID; publicación de expiraciones y recuperación al reconectar. Se cierra el socket si no se puede consultar un estado real. |
| React | `frontend/src/App.tsx`, `styles.css` | Columna «Estado de caché», resaltado y contador por fila, etiquetas de expirado/sin caché, transición y movimiento reducido. Se conservaron los cambios visuales concurrentes encontrados en estos archivos. |
| Pruebas frontend | `frontend/package.json`, `package-lock.json`, `playwright.config.ts` (nuevo), `tests/cache-lifecycle.spec.ts` (nuevo) | Playwright como dependencia de desarrollo y recorrido integral automatizado. |
| Pruebas Java | `backend/src/test/java/com/example/demo/application/service/LinkServiceTest.java`, `domain/model/UrlNormalizerTest.java` (nuevo), `infrastructure/RedisLinkCacheTest.java` (nuevo), `infrastructure/CacheLifecycleIntegrationTest.java` (nuevo) | Servicio, normalización, contrato Redis e integración con persistencia y TTL reales. |
| Documentación | `README.md`, `FIX_VALIDATION.md` (nuevo) | Comportamiento actual, decisiones, comandos reproducibles y resultados. |

## Decisiones técnicas

- La igualdad de URL elimina espacios exteriores, baja esquema/host a minúsculas, elimina puertos predeterminados y equipara ruta vacía con `/`. Conserva ruta, escapes, fragmentos, orden de parámetros y puertos no predeterminados. HTTP y HTTPS son distintos. La redirección conserva la URL original guardada.
- MongoDB decide la identidad mediante un índice único disperso en `normalizedUrl`. Una solicitud perdedora reutiliza el registro insertado por la ganadora y no escribe Redis. Las colisiones de código corto se reintentan.
- `cacheWindowConsumed=true` se guarda en la inserción antes de escribir Redis. Si el proceso o Redis falla entre ambas operaciones, se conserva el enlace y no se reintenta la ventana: la garantía es como máximo una apertura, sin transacción distribuida.
- Redis es la autoridad del TTL activo. MongoDB conserva el enlace y la marca histórica aunque la clave Redis desaparezca. `observedAtMillis` sólo ordena snapshots HTTP/WebSocket; no calcula la cuenta regresiva.
- Los documentos antiguos mantienen sus IDs/códigos. Entre duplicados previos, el menor ID conserva la identidad normalizada; los demás siguen redirigiendo. Los antiguos sin marca muestran «Sin caché activo» y no se cachean por leerlos. La migración es repetible y no borra documentos.
- El nombre del índice `shortCode` coincide con el creado por Spring para evitar conflictos al arrancar con auto-index-creation.

## Pruebas ejecutadas

| Comando / recorrido | Resultado |
| --- | --- |
| `mvn clean test` (opciones batch/no-transfer-progress, `SHORTENER_INTEGRATION_TESTS=true`) | 18 pruebas, 0 fallos, 0 errores, 0 omitidas. |
| `mvn clean package` con integración habilitada, sobre el código final | 18 pruebas, 0 fallos, 0 errores, 0 omitidas; JAR generado. |
| `npm run build` sobre React final | TypeScript y Vite completados. |
| `npm run test:e2e` con Chromium instalado | 1 recorrido integral aprobado, 57,7 segundos; cubre los 12 escenarios. |

Las 18 pruebas Java se distribuyen en 10 de servicio, 3 de normalización, 3 del adaptador Redis y 2 de integración real. Las pruebas usaron JDK 22.0.1 disponible en el equipo, compilando con `release 21`; no se ejecutaron con un runtime Java 21.

| Escenario obligatorio | Comprobación ejecutada |
| --- | --- |
| 1. Crear A inicia 30 s | PTTL real en integración y primer estado obtenido desde el navegador. |
| 2. Consultar a los 10 s conserva aproximadamente 20 s | Espera real de 10 segundos, con tolerancia de ejecución y sin SET/EXPIRE. |
| 3. Cambiar de sección no reinicia | Navegación en Chromium y TTL no creciente. |
| 4. Recargar React recupera TTL | Recarga real de la página y snapshot HTTP/WebSocket. |
| 5. Expirar A no crea otro caché | Expiración natural Redis, notificación WebSocket y retirada del resaltado. |
| 6. Consultar A expirado mantiene expiración | GET posterior con TTL cero. |
| 7. Crear otra vez A reutiliza registro expirado | POST de URL equivalente normalizada conserva ID, código y TTL cero. |
| 8. Crear B inicia otro contador | PTTL nuevo cercano a 30 segundos. |
| 9. Crear B no altera A | A continúa con TTL cero; también se verificaron dos filas activas con distintos TTL. |
| 10. Solicitudes simultáneas no duplican | Barrera de concurrencia en integración, dos POST paralelos en navegador y un único registro por identidad. |
| 11. Redirección funcional | Resolución de servicio y respuesta HTTP 302 con Location original. |
| 12. Reinicios no reactivan ventana consumida | Reinicios reales del proceso Spring Boot y del MongoDB aislado, recarga React y POST de A con TTL cero. |

Adicionalmente se retuvo una respuesta HTTP activa hasta después de la expiración WebSocket: al entregarla tarde, la fila siguió expirada. Se verificó el ancho móvil de 375 px sin desbordamiento de la página y la preferencia de movimiento reducido. Las capturas están en `frontend/test-results/independent-counters.png` y `mobile-counters.png`.

## Entorno y conservación de datos

Se utilizaron exclusivamente `shortener_fix_test_mongo` (27018) y `shortener_fix_test_redis` (6380), con bases nuevas para cada recorrido. No se borraron bases, colecciones ni documentos. Los contenedores habituales de la aplicación en 27017/6379 no fueron reiniciados. Los de prueba se detuvieron al finalizar y conservan sus datos.

El backend de desarrollo se restauró con el JAR corregido y configuración habitual. Su JAR de ejecución y sus logs se encuentran en `.runtime/`, fuera de `backend/target`, para permitir futuras limpiezas Maven sin bloquear esos archivos en Windows. El frontend de desarrollo existente se conservó.

Los intentos iniciales detectaron logs bloqueados en `target`, un conflicto de nombre de índice al arrancar Spring y ajustes necesarios en el selector visual y cierre de procesos de prueba en Windows. Fueron corregidos y el recorrido final terminó aprobado; los intentos fallidos no se contabilizaron como aprobaciones.
