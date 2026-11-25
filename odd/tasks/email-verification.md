# Verificación de cuenta — sin auto-verificación y con reenvío

El enlace de verificación vive en un correo y se consume con un `GET`. Cualquier escáner, prefetch o
tracking del proveedor abre el enlace antes que la persona: la cuenta queda verificada sin que nadie
haya verificado nada, y el clic humano encuentra el token ya consumido.

## Specs

- **S1** — «dime como puedo evitar que las personas salgan como verificadas sin verificarse ellas»:
  ninguna petición pasiva puede habilitar una cuenta. `GET /api/auth/verify?token=…` **no** habilita:
  devuelve una página HTML mínima con un formulario que hace `POST /api/auth/verify/confirm`. Un
  prefetch, un escáner o el tracking del proveedor obtienen la página y nada más.
- **S2** — «Vamos a implementar los puntos 1 y 2» (idempotencia): `POST /api/auth/verify/confirm` con un
  token válido habilita al usuario; repetir la petición con el mismo token responde `200` («ya estaba
  verificada») y no falla. El token **no se borra** al usarlo: se conserva hasta su expiración (24 h) para
  que el doble clic no rompa nada. Token inexistente o expirado → `400` con mensaje explícito.
- **S3** — (punto 1, reenvío) `POST /api/auth/verify/resend` con `{"email": "…"}` responde **siempre**
  `200` con un mensaje genérico, exista o no la cuenta: no se puede enumerar usuarios por acá. Si la
  cuenta existe y no está verificada, **actualiza** el token existente (la columna `user_id` es única, no
  se puede insertar otro) y reenvía el correo.
- **S4** — El reenvío está limitado por IP (3 por hora) igual que el registro. Sin límite es un cañón de
  correo hacia terceros y un consumo de la cuota de Brevo.
- **S5** — La página HTML intermedia no depende de recursos externos, se sirve con
  `Cache-Control: no-store` y no expone el token en un formulario `GET`.

## Tasks

| ID | Specs | Ruta | Commit |
| --- | --- | --- | --- |
| T1 | S1, S2 | delegado | `feat(auth): require an explicit action to verify an account` |
| T2 | S3, S4 | delegado | `feat(auth): resend the verification link` |

## Log

- **L1** — «Ya se ha hecho el deploy, y me ha llegado el correo. Pero no me ha dejado hacer el verify:
  Token inválido o expirado». Diagnóstico: la cuenta **sí** quedó verificada y el token se borró; el enlace
  se abrió solo (prefetch/tracking). La expiración de 24 h no era el problema.
- **L2** — «Si se me ha verificado, y el token esta eliminado, por lo que parece que el enlace se abrió de
  alguna forma en el proceso. Vamos a implementar los puntos 1 y 2». El usuario apaga el click tracking de
  Brevo por su lado; el arreglo de fondo es de diseño, no de proveedor.
- **L3** — Estado de datos: no hay usuarios colgados sin verificar (solo el usuario hizo sign up y limpió a
  mano). No hace falta migración de datos ni reenvío masivo.
- **L4** — El subagente no estaba disponible: el runtime de Pi tiene extensiones rotas
  (`orca-prefill.ts` y `orca-titlebar-spinner.ts` no exportan una factory válida) y el worker no
  arrancaba. Se implementó **inline**, con la misma disciplina de test.
- **E1** — `EmailVerificationFlowTest` 9/9: el GET no habilita, la confirmación sí y es idempotente, el
  token sobrevive al uso, vencido e inexistente dan 400, el reenvío actualiza la única fila y manda un
  mail, cuenta verificada y email desconocido no mandan nada, y el cuarto reenvío por hora responde 429.
- **E2** — Tres tests existentes codificaban el contrato viejo y se actualizaron: dos de
  `AuthRegistrationIntegrationTest` (ahora el GET solo muestra la página) y el paso de verificación de
  `GlobalJourneyIntegrationTest` (agrega el POST de confirmación).
- **E3** — Suite completa: **247 tests, 0 fallos**.

## Cierre

| Tarea | Commit |
| --- | --- |
| T1+T2 | `feat(auth): require an explicit action to verify an account` |
