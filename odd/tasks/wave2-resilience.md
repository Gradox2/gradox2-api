# Wave 2 — Resiliencia operativa

Cinco puntos de la revisión ("Ola 2"): observabilidad acotada, timeouts reales, caché con
invalidación, N+1 en votación y un scheduler idempotente. Sin features nuevas y sin exporter
externo de métricas.

Contexto de despliegue: backend en **Render** (una instancia, TLS del proveedor), frontend en
**GitHub Pages**. Backups y R2 quedan **fuera de alcance** (los lleva otro equipo).

## Specs

- **S1** (punto 13) «Solo Actuator, sin exporter externo»: el perfil `prod` expone
  `health,info,metrics`; los detalles de health dejan de publicarse sin autorización
  (`show-details=when-authorized`); `/actuator/metrics` exige rol `ADMIN` autenticado, de modo que
  el flujo de login y la carga no se filtran a un anónimo. Sin dependencia nueva
  (`micrometer-core` ya viene con Actuator).
- **S2** (punto 12) «El registro sigue, el mail se reintenta/loguea»: el envío de correo tiene
  timeouts (conexión, lectura, escritura) y el cliente S3 tiene timeouts de llamada; el executor de
  correo deja de usar `CallerRunsPolicy` para no frenar hilos de request. Si el SMTP falla o el pool
  rechaza la tarea, el alta de usuario **igual se completa**: se reintenta una vez y se registra el
  error con el destinatario.
- **S3** (punto 10) «10» del plan: la caché de catálogo (grados, cursos, asignaturas) usa Caffeine
  con tamaño máximo y expiración por escritura, y toda escritura invalida las entradas afectadas,
  de modo que un catálogo editado no queda viejo hasta el reinicio.
- **S4** (punto 11) «11» del plan: la votación y el mapeo de archivos no ejecutan una consulta por
  elemento; un test de conteo de queries lo fija como regresión.
- **S5** (punto 9) «Sí, 1 instancia: paginación + idempotencia»: el cierre automático de propuestas
  vencidas se procesa por páginas y es idempotente (una propuesta cerrada no se vuelve a cerrar ni
  vuelve a descontar reputación o auditar). Sin lock distribuido: se asume una instancia.

## Tasks

| ID | Specs | Ruta | Commit |
| --- | --- | --- | --- |
| T1 | S1 | inline | `feat(ops): expose actuator metrics to admin only` |
| T2 | S2 | inline | `fix(mail): bound smtp and s3 timeouts and keep signup alive` |
| T3 | S3 | inline | `perf(cache): bound catalogue cache and evict on writes` |
| T4 | S4 | inline | `perf(votes): remove n+1 queries from tally and file mapping` |
| T5 | S5 | inline | `fix(scheduler): page and deduplicate expired proposal closing` |

## Log

- **L1** — «Listo. Vamos con la siguiente fase. Planificalo y hazme las preguntas pertinentes»
- **L2** — Alcance y decisiones del humano: «Ola 2 completa (13, 12, 10, 11, 9)»; «Solo Actuator, sin
  exporter externo»; «Sí, 1 instancia: paginación + idempotencia»; «El registro sigue, el mail se
  reintenta/loguea».
- **L3** — Decisión previa que acota esta ola: backups de BD y R2 los lleva otro equipo (no entra el
  punto 15); Render es el único punto de despliegue, así que el punto 14 (TLS) se cierra como supuesto
  documentado y no como trabajo.
- **L4** — Decisión de implementación: la rama de esta ola nace de `origin/feat/wave1-hardening`
  (`d5f8a22`) y no de `main`, porque el PR #44 se mergeó sobre un estado anterior de la rama y deja
  fuera `.gitignore`, el gate de deploy de Render y la corrección de roles. Así se evita reabrir
  conflictos en los mismos archivos.
- **E1** — Hallazgo en T3: el catálogo (grados, cursos, asignaturas) **no tiene caminos de escritura en
  runtime**: se siembra por Flyway y ningún servicio lo modifica (verificado con `grep` sobre los
  repositorios). Así, `@CacheEvict` no tiene dónde colgarse; lo que se corrige es el crecimiento sin
  techo y la entrada eterna: Caffeine con `maximumSize` + `expireAfterWrite`. Si aparece un CRUD
  administrativo del catálogo, ese endpoint debe evacuar `degrees`, `courses` y `subjects`.
- **E2** — Evidencia T1: `ActuatorMetricsSecurityIntegrationTest` 5/5. T2: `EmailServiceAsyncTest` 4/4 +
  `MailFailureToleranceIntegrationTest` 1/1. T3: `CatalogueCacheTest` 4/4 y las integraciones de
  catálogo (16 tests) sin regresiones.
