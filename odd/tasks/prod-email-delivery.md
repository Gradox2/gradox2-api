# Correo de producción — verificación de cuenta

El registro exige verificación por email y en producción el correo nunca llega: los usuarios quedan
con la cuenta creada pero deshabilitada, sin forma de verificarse salvo tocar la base de datos.

## Specs

- **S1** — «El correo en la instancia de producción no llega a los usuarios, cuando se registran para
  verificar la cuenta». El envío de producción tiene que salir por un transporte que Render permita.
- **S2** — «Brevo (HTTP API, 300/día)»: el transporte de producción es la API HTTP de Brevo sobre 443,
  con `BREVO_API_KEY` y un remitente verificado (`MAIL_FROM`).
- **S3** — El alta de usuario no depende del correo: si el proveedor falla, la cuenta queda creada y
  pendiente, el fallo se reintenta una vez y queda en el log con el destinatario (WAVE2 S2).
- **S4** — Un transporte mal configurado no arranca en silencio: con `app.mail.transport=brevo` y sin
  `BREVO_API_KEY` (o sin `MAIL_FROM`), el arranque falla con un mensaje que nombra la variable faltante.
- **S5** — «Esperar el fix de correo y mergear todo junto»: Ola 1, Ola 2 y este fix viajan en un solo
  PR y un solo deploy.

## Tasks

| ID | Specs | Ruta | Commit |
| --- | --- | --- | --- |
| T1 | S1, S2 | inline | `fix(mail): send production mail through brevo over https` |
| T2 | S3, S4 | inline | (con T1) |
| T3 | S5 | inline | merge de `feat/wave1-hardening` a `main` + push |

## Log

- **L1** — «Antes de seguir, vamos a hacer 2 cosas. Vamos a revisar y pasar todo a main, dejar todo
  actualizado, y quiero que me ayudes a arreglar lo siguiente: El correo en la instancia de producción
  no llega a los usuarios, cuando se registran para verificar la cuenta»
- **L2** — Decisiones: «Brevo (HTTP API, 300/día)» y «Esperar el fix de correo y mergear todo junto».
- **L3** — Diagnóstico: el perfil de producción usaba `smtp.gmail.com:587` (STARTTLS). **Render bloquea
  la salida SMTP en 25, 465 y 587**, así que ese transporte no puede funcionar en producción por más
  que las credenciales sean válidas. Local sigue en SMTP (MailHog/consola) y test usa SMTP mockeado.
- **L4** — Decisión de implementación: se introduce `MailTransport` con dos implementaciones
  (`SmtpMailTransport`, `BrevoMailTransport`) elegidas por `app.mail.transport`. `EmailService` conserva
  el executor, el reintento y la garantía de no propagar el fallo al alta.
- **L5** — Pendiente operativo (humano): crear cuenta Brevo, verificar `MAIL_FROM`, generar
  `BREVO_API_KEY` y cargarla en Render (Environment). Sin eso el arranque falla a propósito (S4).
- **L6** — Segunda causa probable, independiente: si `APP_BASE_URL` no es la URL pública del backend con
  `https://`, el correo llega pero el enlace de verificación no funciona. Queda en la checklist de
  DEVOPS para verificar tras el deploy.
- **E1** — `BrevoMailTransportTest` 4/4 (contrato de la API, `api-key`, remitente, rechazo 401 → fallo de
  entrega, y arranque sin API key), `EmailServiceAsyncTest` 4/4 y `MailFailureToleranceIntegrationTest` 2/2.
- **E2** — Suite completa: **235 tests, 0 fallos** (`./mvnw test`, 2m09s).

## Cierre

| Tarea | Commit |
| --- | --- |
| T1+T2 | `fix(mail): send production mail through brevo over https` |
| T3 | merge `feat/wave1-hardening` → `main` (PR #45) |
