# Adaptación del frontend — Ola 1, Ola 2 y verificación de correo

Este documento es para el equipo de **`gradox2.github.io`**. El backend cambió en tres tandas: la Ola 1
(seguridad y datos), la Ola 2 (resiliencia operativa) y la verificación de cuenta. Acá está **solo lo que
afecta al cliente**: nada de esto requiere tocar el diseño visual, pero sí hay contratos que rompen.

Regla de oro para todo el documento: **dependé de `errorCode`, no del código HTTP solo**. El backend
mantiene el par `{ status, errorCode, message }` y los códigos son estables; los status pueden agruparse.

---

## 1. Cambios que rompen (requieren acción)

### 1.1 El enlace de verificación ya no verifica

**Qué pasó.** El enlace del correo apuntaba a `GET /api/auth/verify?token=…` y ese GET habilitaba la
cuenta. Un escáner de correo, un prefetch o el tracking del proveedor abrían el enlace antes que la
persona: la cuenta quedaba verificada sin intervención humana y el clic real encontraba "Token inválido
o expirado".

**Qué hay ahora.**

| Endpoint | Qué hace | Respuesta |
| --- | --- | --- |
| `GET /api/auth/verify?token=…` | Devuelve una **página HTML** con un botón. **No habilita nada** | `200 text/html` o `400 text/html` si el token no sirve |
| `POST /api/auth/verify/confirm` | Verifica. Idempotente | `200` `"Cuenta verificada con éxito"` / `"La cuenta ya estaba verificada"` / `400` |
| `POST /api/auth/verify/resend` | Reenvía el correo | `200` siempre genérico · `429 RATE_LIMIT_EXCEEDED` |

**Qué tiene que hacer el frontend, hoy:** nada obligatorio. El correo apunta al backend
(`APP_BASE_URL/api/auth/verify`), así que el usuario ve nuestra página intermedia y confirma ahí. Es
funcional y segura, pero **es HTML del backend, no una pantalla de React**.

**Qué recomendamos que hagan (deuda acordada):**

1. Crear una ruta propia, por ejemplo `https://gradox2.github.io/verify?token=…`, que muestre una
   pantalla con el branding del sitio y un botón "Verificar mi cuenta".
2. Ese botón hace `POST {API}/api/auth/verify/confirm?token=…`.
3. Avisarnos para cambiar `APP_BASE_URL` del correo a la URL del frontend. El token viaja igual en el
   query string, así que el cambio es solo de destino.

**El botón tiene que ser un `POST`.** No lo implementen como `fetch` en un `useEffect` del montaje de la
pantalla: eso reintroduce exactamente el bug que acabamos de arreglar, porque el prefetch cargaría la
página y dispararía la verificación sin que nadie toque nada.

### 1.2 `POST /api/auth/verify/resend` (endpoint nuevo)

```http
POST /api/auth/verify/resend
Content-Type: application/json

{ "email": "alumno@rai.usc.es" }
```

- Responde **siempre lo mismo**, exista o no la cuenta y esté o no verificada:
  `"Si la cuenta existe y está pendiente de verificación, te enviamos un correo nuevo."`
- **No revela si el email está registrado.** No muestren un mensaje distinto según la respuesta: sería
  filtrar la existencia de cuentas.
- Límite: **3 por hora por IP** → `429 RATE_LIMIT_EXCEEDED`.
- No manda nada si la cuenta ya estaba verificada, ni si no existe.

**Dónde usarlo:** en la pantalla de login, cuando el backend responde `401 UNAUTHENTICATED_ACCESS` con
mensaje `"Usuario no verificado"`. Ahí corresponde ofrecer "reenviar verificación" en vez de decir
"contraseña incorrecta".

### 1.3 Cambiar la contraseña cierra todas las sesiones

`PUT /users/me` con contraseña nueva (y el override administrativo de grado) incrementa la versión del
token:

- El **access token actual deja de valer** → `401 UNAUTHENTICATED_ACCESS`.
- Los **refresh tokens se revocan** → el refresh devuelve `401`.

**Acción:** después de cambiar contraseña, o si una llamada devuelve `401` con token que creías válido,
**forzar logout local y redirigir al login**. No reintenten con el mismo token ni asuman que el refresh
va a salvarlos: en este caso también está revocado.

### 1.4 CORS no acepta credenciales y solo admite orígenes configurados

- `allowCredentials` es **`false`**: la sesión viaja **solo** en `Authorization: Bearer …`.
- Si en algún `fetch` tienen `credentials: 'include'`, **sáquenlo**: no aporta y confunde el diagnóstico.
- Solo los orígenes de la lista configurada reciben `Access-Control-Allow-Origin`. Si van a servir el
  frontend desde un dominio nuevo, hay que agregarlo del lado del backend (variable
  `APP_CORS_ALLOWED_ORIGINS`). Hoy: `https://gradox2.github.io` y, en local, los puertos de dev.

### 1.5 Descargar material de propuestas ajenas ahora está restringido

`GET /file-proposals/{id}/download` exige **pertenecer al grado del recurso** y poder verlo:

| Caso | Respuesta |
| --- | --- |
| Otro grado | `403` `DEGREE_SCOPE_FORBIDDEN` |
| Contenido `PRIVATE` ajeno | `404` `NOT_FOUND` |
| Sin sesión / token inválido | `401` `UNAUTHENTICATED_ACCESS` |

**Acción:** manejar `DEGREE_SCOPE_FORBIDDEN` con un mensaje de "esto pertenece a otro grado", no como
error genérico. Antes un usuario de otro grado obtenía URLs prefirmadas de material no aprobado.

### 1.6 Votar sin sesión ahora es `401`, no un límite compartido

El bucket de votos dejó de ser compartido entre anónimos. Si el botón de voto está visible sin sesión,
va a recibir `401`. **Acción:** deshabilitar o redirigir a login cuando no hay sesión, en vez de dejar que
falle.

### 1.7 Voto o score duplicado responde `409`, no `500`

La base ahora tiene un índice único por `(file_id, voter_id)`. Dos peticiones simultáneas del mismo
usuario ya no crean dos votos: la segunda responde `409` con `DATA_INTEGRITY_ERROR` o
`ALREADY_EXIST_ERROR`. **Acción:** interpretarlo como "ya votaste" y refrescar el estado, no mostrar
"error inesperado".

---

## 2. Comportamientos que conviene conocer (no rompen)

| Cambio | Efecto en el cliente |
| --- | --- |
| Caché de catálogo con TTL de 10 min | Grados, cursos y asignaturas pueden tardar hasta 10 minutos en reflejar un cambio hecho por migración/administración. No asumir lectura instantánea. |
| Timeouts de SMTP y S3 | Un fallo del proveedor de correo o de almacenamiento ahora **corta** en segundos en vez de colgar. El registro se completa igual aunque el correo falle: la cuenta queda pendiente. |
| Rate limit por IP real | Login: 5 por 15 min. Registro: 3 por hora. Reenvío de verificación: 3 por hora. Todos con `429 RATE_LIMIT_EXCEEDED`. Conviene un mensaje con cuenta atrás. |
| `/actuator/metrics` solo `ADMIN` | No es para el frontend. `/actuator/health` sigue público pero **sin detalles**. |
| Índices y eliminación en S3 post-commit | Sin cambio de contrato. |

---

## 3. Tabla de error codes (los que puede devolver el cliente)

| HTTP | `errorCode` | Cuándo |
| --- | --- | --- |
| 400 | `DEGREE_REQUIRED` | Registro sin `degreeId` |
| 400 | `DEGREE_IMMUTABLE` | Intento de cambiar el grado propio |
| 400 | `DEGREE_LAST_MASTER` | Dejar un grado sin masters |
| 400 | `VALIDATION_ERROR` | Payload inválido (incluye detalle por campo) |
| 401 | `UNAUTHENTICATED_ACCESS` | Token ausente/expirado/revocado, credenciales malas, **usuario no verificado** |
| 403 | `DEGREE_SCOPE_FORBIDDEN` | Operar fuera del propio grado |
| 403 | `INVALID_ROLE_OPERATION` | Acción no permitida por el rol (p. ej. `ADMIN` votando) |
| 404 | `NOT_FOUND` | Recurso inexistente o no visible |
| 409 | `ALREADY_EXIST_ERROR` | Username/email ya registrados |
| 409 | `DATA_INTEGRITY_ERROR` | Carrera perdida (voto duplicado, cuota de subidas) |
| 409 | `CONCURRENT_MODIFICATION` | Edición concurrente del mismo recurso |
| 409 | `PROPOSAL_CLOSED` | Votar una propuesta ya cerrada o vencida |
| 429 | `RATE_LIMIT_EXCEEDED` | Límite por IP alcanzado |

---

## 4. Roles: hay cuatro, no tres

`GUEST` (sin sesión), `USER`, `MASTER` (gobierna **su grado**), `ADMIN` (opera la plataforma).

- `/admin/**` exige `ADMIN`. Un `MASTER` **no** entra al panel: recibe `403`.
- El login y `/users/me` devuelven `isPlatformAdmin: true` para el `ADMIN`. Usen ese flag para mostrar el
  panel, no el rol comparado a mano.
- El `ADMIN` **no vota, no propone y no es candidato a promoción** (`INVALID_ROLE_OPERATION`). No le
  muestren botones de voto ni de propuesta.

---

## 5. Lo que **no** cambió

- Forma de autenticarse: `Authorization: Bearer <token>` en cada request, sin cookies.
- Rutas existentes de archivos, asignaturas, cursos, grados, insignias, estadísticas, propuestas y
  comentarios: mismos paths y mismos campos.
- El registro sigue devolviendo `200` con token `null` y la cuenta **pendiente de verificación**.
- `GET /files/**`, `/subjects/**`, `/courses/**`, `/degrees/**`, `/badges/**`, `/stats/**` siguen
  públicos.

---

## 6. Checklist de QA antes de publicar

- [ ] Registro nuevo → llega el correo → el enlace abre la **página con botón** → confirmar → login OK.
- [ ] Abrir el enlace dos veces: la segunda dice "ya estaba verificada", no error.
- [ ] Abrir el enlace desde un cliente de correo con previsualización y comprobar que **no** verificó la
  cuenta por sí solo (el usuario sigue sin poder entrar hasta confirmar).
- [ ] Login sin verificar → `401 UNAUTHENTICATED_ACCESS` con "Usuario no verificado" → botón de reenvío.
- [ ] Reenviar 4 veces: la cuarta da `429` con mensaje claro.
- [ ] Cambiar contraseña → la app cierra sesión y pide login de nuevo (no reintenta con el token viejo).
- [ ] Votar dos veces rápido → `409` tratado como "ya votaste".
- [ ] Descargar propuesta de otro grado → mensaje de grado, no error genérico.
- [ ] Votar sin sesión → redirige a login, no `401` crudo.
- [ ] Admin ve el panel por `isPlatformAdmin`; un `MASTER` no lo ve.
