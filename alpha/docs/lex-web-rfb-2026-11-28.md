# LEX web: escritorio RFB sobre un stream de adb — 2026-11-28

Estado: implementado y probado de forma interactiva el 2026-11-27/28. El flujo
completo por WebUSB llegó a `live` en un Chrome de prueba. Las cifras de
latencia y caudal que aparecen aquí se midieron sobre un túnel TCP, no sobre
WebUSB, y se marcan como tales.

Dispositivo: SM-G973F (`beyond1lte`), Xtigervnc a 2280x1080, Lambda Core con el
trabajo `system.desktop`.

## Contexto

La página mostraba el escritorio como capturas: `scrot` por una llamada
`shell:` y una imagen nueva bajo demanda. Dos problemas la hacían inservible
para interactuar:

- Varias shells concurrentes compartían una única cola de paquetes. Los
  paquetes ADB completos se los quedaba la shell equivocada: una lectura de
  estado o un clic podía consumir el `WRTE` del fotograma.
- Codificar un PNG costaba 326 ms por fotograma. Eso fijaba el techo en unos
  pocos fps y no había forma de arrastrar ni escribir con soltura.

El teléfono ya tenía Xtigervnc escuchando en `127.0.0.1:5901` con VncAuth y la
contraseña en `/data/local/lex/vncpass` (root, modo 600). No hacía falta un
demonio nuevo: faltaba el transporte hasta el navegador.

## Qué cambió

### `LexAdb.openStream()`

Un enlace `shell,v2,raw:` de larga duración, con el servicio
`shell,v2,raw:nc 127.0.0.1 5901`. El canal que devuelve imita lo justo de un
WebSocket para que `Websock.attach()` de noVNC lo consuma:

- **stdin**: envoltura shell v2 con id 0, longitud de 4 bytes y los bytes.
- **stdout**: id 1. **stderr**: id 2, guardado truncado solo para diagnóstico.
  **estado de salida**: id 3.
- **Enrutado por enlace**: cada shell o stream registra su propio handler en un
  `Map` con su id local; `feed()` despacha por `arg1`/`arg0`. Dos shells
  concurrentes ya no compiten por una cola común.
- **Escrituras serializadas** en una cadena de promesas: Chrome rechaza
  `transferOut` solapados. Las lecturas despiertan a los que esperan
  (`waiters`) en cuanto llega un paquete; el temporizador de 100 ms es solo un
  respaldo.

### Cliente de escritorio

- El core de noVNC 1.x y pako están vendorizados en `public/novnc/`, bajo
  MPL-2.0, con `LICENSE.txt` (incluye las excepciones de `des.js` y pako). No
  se han modificado.
- `public/lex-desktop.js` construye
  `new RFB(container, channel, { credentials: { password }, shared: true })`,
  con `scaleViewport` y `showDotCursor` activos, sin `resizeSession`, y
  calidad 6 / compresión 2.
- El cursor lo dibuja el cliente: el movimiento no espera ni depende de
  tráfico de vuelta para pintar el puntero. Los movimientos sí envían eventos
  RFB de puntero al teléfono.
- noVNC negocia *continuous updates* y las activa cuando el servidor anuncia
  que las soporta; las actualizaciones son push, no sondeos.

### Flujo de la página

`connect → identify → consent → password → stream → RFB`

1. WebUSB abre el dispositivo y el cliente ADB se autentica. La identidad se
   comprueba con `getprop ro.build.flavor`: tiene que contener `lambda` y
   `beyond1lte`.
2. El consentimiento se pide en el teléfono: `ConsentActivity` a pantalla
   completa con un token nuevo por intento, y un fichero
   `consent-<token>` con `accepted` o `rejected`. No se lee nada de la pantalla
   antes de aceptar.
3. Si `desktop_status` dice que está parado, se envía el trabajo tipado
   `system.desktop` con `action: start` y se espera a que termine.
4. La contraseña se lee con `cat /data/local/lex/vncpass` (hace falta adb
   root).
5. Se abre el stream con `openStream('nc 127.0.0.1 5901')` y se attacha RFB.

## Verificación (2026-11-27/28)

- El flujo completo por WebUSB, en un Chrome de prueba, llegó al estado
  `live`.
- Interacción real: un arrastre movió una ventana de `x=2` a `x=352` y de
  vuelta, comprobado en la geometría X de la ventana; escribir `echo rfb-live`
  en el terminal del escritorio lo ejecutó en el teléfono.
- Sobre un túnel TCP (no WebUSB):

  | Medida | Valor |
  |---|---|
  | puntero → daño en pantalla | ~15 ms de mediana |
  | fotograma Tight completo | ~35 KB / ~107 ms |
  | caudal del túnel | 4,3–4,5 MB/s |

- El cursor se pinta en local y no necesita tráfico de vuelta para
  actualizarse; los movimientos sí envían eventos RFB de puntero.
- *Continuous updates* soportadas y activadas.
- Se eligieron q6/c2. Con c0/c1 los bytes se duplicaban sin ganar latencia.
- Maquetación del día: la etapa pasó de 1280 a 1720 px de ancho máximo. La
  pantalla completa necesitó una regla extra: la regla de estado
  `main[data-state="live"] .stage` tiene más especificidad que
  `.stage:fullscreen` y forzaba `display: block`; se restauró `display: flex`.
  Marco negro para el letterbox y barra compacta.
- Pintado de la página medido a 144 fps sin long tasks (Chrome 157, Intel UHD,
  144 Hz). El tirón que queda en los cambios grandes viene del codificador del
  teléfono y del pintado del canvas en el hilo principal, no del CSS.

## Límites

- La contraseña VNC exige adb root. Sin él la página no puede abrir el
  escritorio y lo explica.
- El movimiento continuo a pantalla completa va en la clase de 3–6 fps: cada
  fotograma completo cuesta unos 100–300 ms. Las actualizaciones pequeñas son
  mucho más rápidas.
- Las cifras de latencia y caudal son de un túnel TCP. En WebUSB se verificó el
  flujo completo hasta `live` y la interacción (arrastre y teclado), pero no se
  midió allí el caudal; queda pendiente. La latencia del primer fotograma
  depende de la carga del escritorio.
- La geometría es 2280x1080; la página la escala y hace letterbox en negro.
- Xtigervnc escucha solo en `127.0.0.1:5901`. El stream de adb es el único
  camino: el navegador no puede crear un *forward* TCP del host.
- Un teléfono a la vez. El servidor adb del ordenador tiene que estar parado
  (`adb kill-server`) mientras el navegador posee la interfaz.
