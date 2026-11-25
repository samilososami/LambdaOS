# LambdaOS — ideas para implementar

Registro de ideas y decisiones del proyecto. Las propuestas de este documento se implementarán y validarán cuando la base del sistema operativo esté lista. No son un listado de funciones que ya estén disponibles.

Las nuevas ideas se añadirán aquí con fecha, sin borrar el historial de decisiones. Si cambia una idea, se indicará qué sustituye. El texto para GitHub se mantiene como borrador hasta comprobar sus promesas.

## TO-DO — pendientes de verificar o corregir

Lista viva de comprobaciones y arreglos aplazados. Se actualiza aquí, sin tocar el historial fechado.

- [ ] **AP · NTLM con Windows real** (2026-11-23, parcial): el PAC de WPAD se sirvió y Windows usó el proxy (sondas NCSI con 407), pero no se capturó ningún NetNTLM. Siguiente: probar un PAC sin `DIRECT` de respaldo para que el navegador no pueda saltarse el proxy; registrar `Proxy-Authorization`; probar navegación HTTP y HTTPS (CONNECT), SSO de WinHTTP/Edge/Chrome, disparos LLMNR/NBT-NS/SMB y equipos en dominio; documentar el resultado.
- [ ] **AP · portal cautivo en One UI**: no aparece el banner/botón “Iniciar sesión en la red”. Investigar con `dumpsys connectivity`, RFC 8908 (HTTPS con certificado) o DHCP 114, y probar en otros fabricantes.
- [ ] **AP · portal + captura**: verificar con un cliente real (móvil/portátil) el flujo combinado, incluido `/wpad.dat` en el puerto 80 del portal.
- [ ] **AP · tests de velocidad**: los tests web a través del AP no completan; reproducir con sniffer solo si llega a importar.
- [ ] **AP · modos y plantillas**: repetidor como modo de primera clase, AP oculto, allowlist MAC, más plantillas de portal (Facebook, Instagram, Microsoft, Apple, hotel/aeropuerto), plantilla propia, y capturas portal/WPS/NTLM unificadas y exportables.
- [x] **UCM/FDT · pantalla sin salida** (0.2.0-operator.21): lock task real con Lambda como device owner (`dpm set-device-owner`); verificado en el S10 que Home no sale y que Cerrar/Parar liberan el modo. En ROM, el SetupWizard deberá provisionar el device owner.
- [x] **Worker renombrado a `RadioWarden`** (0.2.0-operator.21): clase, scripts de despliegue, tests y documentación; arranque, journal y `radio_status.available` verificados. Sustituye a “Warden” como nombre corto.
- [ ] **UCM/SUCM/FDT · temperatura física real**: medir la tapa/piel con termómetro tras un soak de 300 s y comparar con SoC y batería; decidir si el objetivo de UCM debe ser SoC, cuerpo/batería o mixto, y documentar la diferencia.
- [ ] **FDT · flujo completo**: verificar en el móvil el selector objetivo→hold (30/60/120/300), los botones Monitor cooling/Smart cooling y que el FDT no quede en la pila; probar un soak de 300 s y el corte de seguridad a 75 °C.
- [x] **Temperature Monitor · velocidad** (0.2.0-operator.21): la tasa no se muestra hasta tener ≥8 s de muestras; a los 2 s aparece “Calculando velocidad…” y luego el valor estable. Batería/cuerpo sigue como referencia lenta.
- [x] **UCM Ultra · AP activo** (0.2.0-operator.21/22): si hay un `radio.ap` en cola o ejecución, la UI bloquea el inicio y pide detener el AP primero (el worker solo ejecuta un job a la vez). Verificado con un AP real (`kiosktest`); Wi-Fi restaurado al cancelar el AP.
- [x] **Worker/Warden · persistencia frente a Core** (0.2.0-operator.26): el worker re-resuelve el ContentProvider de Core y sobrevive a un force-stop/actualización de la app (mismo PID, `radio_status.available` al volver). Sigue pendiente el servicio persistente de ROM tras un reinicio.
- [x] **LEX · wallpaper y branding** (0.2.0-operator.24): wallpaper horizontal 2280×1080 con el degradado de puntos y la Λ del setup (`alpha/lex/make_wallpaper.py`); se aplica repuntando el symlink `kali-16x9/default` y el default de xfconf a `/usr/share/backgrounds/lambdaos/lambdaos.png`. Verificado en el S10; el truco fue cambiar el fichero que carga xfdesktop, no solo sus claves.
- [x] **LEX · teclado en el móvil** (0.2.0-operator.24): al tocar el lienzo/búsqueda de noVNC aparece el IME de Android y las teclas llegan al escritorio; probado escribiendo `term` en whisker y `ls` en Terminal Emulator.
- [ ] **LEX · rendimiento y térmica**: a 2280×1080 en reposo con el visor abierto se midió ~8,2 % de CPU de la app, Xtigervnc ~97 MB RSS, BIG 35 °C y batería 28,2 °C; falta medir fps y una sesión sostenida con carga real.
- [x] **LEX · web por ADB** (0.2.0-operator.26): dashboard en `alpha/web/lex-dashboard` (Node sin dependencias, 127.0.0.1:7788) con métricas, jobs, captura, inicio/parada y escritorio noVNC embebido; probado de punta a punta. Ver `alpha/docs/lex-web-2026-11-23.md`.
- [x] **LEX · escáner Wi-Fi Direct renovado** (0.2.0-operator.28): receiver de peers/estado/descubrimiento, renovación automática tras el corte de ~2 min del framework, BUSY tratado como “ya buscando”, pantalla encendida y estado vacío honesto. Verificado en el S10: dos TVs LG encontradas tras 150 s de escaneo y rearme <1 s tras `P2P_FIND_STOPPED`/`BLOCK_DISCOVERY`.
- [x] **Pantalla externa → sugerencia LEX** (0.2.0-operator.28): `CoreService` escucha DisplayManager y publica “Pantalla externa detectada · ¿Abrir LEX en ella?” con Abrir LEX/Ahora no. Verificado con `overlay_display_devices` 1280×720 (FLAG_PRESENTATION): `LexMonitorActivity` creó la Presentation y arrancó el escritorio. Falta el monitor USB-C físico.
- [x] **Web · consentimiento de conexión** (0.2.0-operator.28): RPC tipados `connect`, `connect_status` y `connect_forget`; notificación con Permitir/Permitir siempre/Denegar; hosts de confianza persistidos en preferencias privadas. Verificado tocando Permitir siempre en la sombra; la página conecta, carga métricas/jobs/captura y arranca/para el escritorio. Ver `alpha/docs/lex-connect-2026-11-24.md`.
- [x] **Web · WebUSB sin puente** (0.2.0-operator.29): el puente local y sus `/api/*` se han eliminado; `server.mjs` solo sirve estáticos y la página habla ADB por WebUSB (CNXN/AUTH con clave RSA WebCrypto, OPEN/WRTE/OKAY/CLSE, `shell:` binario para capturas). `claimInterface` reintenta 3 veces y, si adb retiene la interfaz, explica `adb kill-server`; «Conectar» reutiliza el permiso USB sin volver a mostrar el selector. Auto-chequeo nuevo: `test-adb-claim.cjs` (selección de interfaz, reintento tras EBUSY, error sin interfaz) y `selfTest()` del protocolo. Falta la prueba viva con el selector de dispositivo y el aviso nativo de depuración USB.
- [x] **LEX · pantalla inalámbrica LAN** (0.2.0-operator.29): además de Wi-Fi Direct/Miracast, descubre `_googlecast._tcp` y `_airplay._tcp` por mDNS y resuelve la IP; la lista mezcla protocolos y muestra `Miracast`, `Chromecast · LAN` o `AirPlay · LAN`. Verificado en el S10: Sony KD-43X81K (AirPlay, Chromecast), SHIELD Android TV (Chromecast), BRAVIA y altavoces; dos TVs LG aparecen como receptores Miracast. La conexión directa la rechaza `DisplayManagerService` (la app no está firmada por plataforma); la app abre el selector del sistema, que solo lista TVs si `wifi_display_on` está activo — comprobado: con el ajuste a 1 aparecen las dos LG con `canConnect true`. Chromecast/AirPlay se detectan pero este build no tiene emisor para ellos; ver `alpha/docs/lex-wireless-2026-11-24.md`.
- [x] **LEX · sonda de escritorio tolerante** (0.2.0-operator.28): cada puerto se reintenta 3 veces y el estado se borra solo tras dos comprobaciones fallidas consecutivas. La prueba cazó un falso “detenido” bajo carga durante un start; tras el arreglo, un start real desde la web siguió `running` a los 30 s y a los 75 s.
- [x] **LEX · receptor sin emisor, estado honesto** (0.2.0-operator.30): la lista mezcla Miracast (Wi-Fi Direct) con Chromecast/AirPlay (mDNS) y etiqueta cada receptor; si el receptor no es Miracast y el build no trae emisor, el botón queda deshabilitado con «Sin emisor en este build» en lugar de abrir los ajustes del sistema. Verificado en el S10 con `uiautomator dump`: Sony KD-43X81K deshabilitada, TV LG habilitada con «Conectar pantalla». El envío real a la Sony depende de la idea del emisor AirPlay/Cast.
- [ ] **WebUSB · prueba viva de punta a punta** (2026-11-24, 0.2.0-operator.30): el claim ya está resuelto —tras `adb kill-server` Chrome reclama la interfaz y el AUTH llega a CNXN—, pero la conexión completa depende de un toque humano en el aviso de Android y sigue sin cerrarse. Guion:
  1. **Preparación**: S10 desbloqueado, depuración USB activa, cable de datos; en el PC `adb kill-server`; servidor estático en `http://127.0.0.1:8881` (contexto seguro para WebUSB); Chrome o Edge.
  2. **Conectar**: pulsar «Connect over USB», elegir el S10 (`04e8:685d`) en el selector; en el teléfono tocar «Permitir» y repetir la prueba con «Permitir siempre desde este equipo».
  3. **Reset tras aceptar**: aceptar el aviso resetea la función USB y aborta el primer `shell:`; la página debe reabrir sola una vez (`usbCoreRetry`) y completar `connect`. Si no, mirar en consola `LexAdb USB read failed:` y el texto de `lastError`.
  4. **Consentimiento LEX**: aprobar la notificación de Lambda Core y comprobar que `connect_status` devuelve el host recordado.
  5. **Telemetría**: batería, Wi-Fi, `/data`, versión de Core y worker; esperar 2–3 ciclos del sondeo (5 s) y verificar que no se cruzan lecturas (un solo stream ADB a la vez).
  6. **Captura**: pedir la captura y validar el PNG (la firma se busca dentro del stream porque puede haber eco del `shell:`); que salga el aviso del sistema o la sombra es esperado.
  7. **Escritorio**: Start/Stop desde la web; el job `system.desktop` debe seguir `running` a los 30 s y quedar `stopped` tras parar.
  8. **Permiso del navegador**: recargar la página no debe volver a mostrar el selector (`navigator.usb.getDevices()`); desenchufar y reenchufar sí lo muestra (Chrome invalida el permiso al replug, no es un bug nuestro).
  9. **Cierre limpio**: `adb start-server`; `adb devices` debe listar el S10 como `device` (no `unauthorized`); en logcat, anotar qué fichero de claves cargó `adbd_auth`.
  - **Criterio de salida**: connect + métricas + captura + start/stop en una sola sesión sin tocar el cable, README actualizado con lo observado y notas de udev (Linux) y WinUSB/Zadig (Windows).
  - **Fallos conocidos**: `Could not claim the ADB interface…` → adb sigue vivo; AUTH repetido → clave rechazada o aviso no aceptado; `The transfer was cancelled` justo tras aceptar → reset USB esperado (la página reintenta una vez); timeout de Core → worker caído o consentimiento denegado.
- [ ] **ROM · ruta de claves ADB (arreglo de ROM)** — prioridad alta: es la causa de que «Permitir siempre» no sirva y de que WebUSB vuelva a pedir permiso. `adbd` carga solo `/adb_keys` (symlink a `/product/etc/security/adb_keys`), mientras el framework escribe las claves aceptadas en `/data/misc/adb/adb_keys`. Evidencia: logcat `adbd_auth: loading keys from /adb_keys`; `lambdaos-web@chrome` está en el fichero de usuario y aun así reaparece el aviso tras `ctl.restart adbd`. Pasos:
  1. **Localizar el override** (el árbol AOSP de build no está en este checkout; hay que abrirlo aparte): mirar si `adbd` lee una ruta configurable (tipo `persist.adb.key_path`) o si el producto fuerza `/adb_keys` desde `device/samsung/beyond1lte`/`vendor/lineage` o desde el symlink de `/product`. En AOSP el cargador lee `/data/misc/adb/adb_keys`; si aquí solo cambia el symlink, el arreglo puede ser una línea de makefile en vez de tocar C++.
  2. **Parche mínimo si el override no existe**: hacer que adbd cargue ambas rutas (producto y usuario), sin duplicar claves; respetar propietario/modo y la etiqueta SELinux de `/data/misc/adb` (`adb_data_file`), y que la carga ocurra antes de bajar privilegios.
  3. **Build**: recompilar `adbd`/imagen de producto por la línea FCM-v4 habitual y empaquetar el ROM; el flash queda sujeto a una petición explícita y no forma parte de esta tarea.
  4. **Verificación en dispositivo**: aceptar «Permitir siempre» en el build nuevo, `adb shell setprop ctl.restart adbd` y `adb devices` debe seguir `device` sin aviso; repetir tras un reinicio completo y comprobar que WebUSB conecta sin volver a preguntar.
  - **Criterio de salida**: dos reinicios de adbd y uno del teléfono sin que reaparezca el aviso para una clave ya aceptada, y coherencia entre `/adb_keys` y `/data/misc/adb/adb_keys`.
  - Nota aparte: aceptar el aviso resetea la función USB y aborta el primer shell; la página ya reintenta una vez tras el reset.
- [ ] **Consentimiento · identidad**: el allowlist usa el nombre visible que envía la página, no una identidad criptográfica; cualquier host ya autorizado por Android puede llamar a Core directamente. Valorar emparejar el nombre con la huella de la clave ADB.
- [ ] **WebUSB · clave ADB no extraíble** (revisión de seguridad 2026-11-25): `adb-webusb.js` exporta la clave RSA como JWK a `localStorage`. Endurecer: generarla con `extractable=false`, guardar el `CryptoKey` privado en IndexedDB (structured clone), conservar solo la parte pública (n, e) para construir el blob ADB y aceptar el re-prompt de Android al cambiar de clave. Mitigación actual: origen propio en localhost, CSP estricta sin assets externos y clave de laboratorio; el riesgo requiere ejecución de script en ese origen. Probar en el S10 antes de cerrarlo.
- [ ] **LEX · identidad de los puertos 6080/5901** (revisión de seguridad 2026-11-25): `start.sh` da el servicio por bueno con un `grep` del listener en `/proc/net/tcp` y un match de subcadena en la cmdline de websockify; un proceso local podría ocupar el puerto y el estado diría «running». Endurecer: mapear el inodo del socket a `/proc/<pid>/fd` y compararlo con `websockify.pid`; si el puerto lo tiene otro, fallar cerrado. Contexto que rebaja el riesgo: todo el chroot corre como root, así que el atacante ya necesitaría root; aun así, se anota.
- [ ] **UI de hosts de confianza**: `connect_forget` existe por RPC, pero Operator no permite revisar ni olvidar hosts; añadir esa pantalla cuando haya más de uno.
- [ ] **LEX · modos 2–3 en hardware**: `LexMonitorActivity` (sin monitor: pantalla de “conecta un monitor” verificada; Presentation verificada con pantalla de superposición simulada) y `LexWirelessActivity` (dos TVs LG como receptores Miracast y selector del sistema con `canConnect true`) están integrados, pero faltan probar Presentation/WebView en un monitor USB-C, el trackpad RFB y una sesión de espejo Wi-Fi Display real (requiere tocar la TV en el selector; el audio DP sigue pendiente).
- [ ] **LEX · modo 2 tras revisión**: el cliente RFB no reintenta tras un error y la actividad no consulta `desktop_status`, así que un reinicio/parada externa del escritorio deja la Presentation muerta hasta recrear la actividad. Añadir reintento con backoff y sondeo; probar con monitor real.
- [ ] **LEX · pantalla extendida sin receptor**: investigación cerrada: Miracast solo duplica la pantalla por defecto y su receptor debe anunciarse; Chromecast exige Cast Streaming (`openscreen`/libcast, integración C++/NDK) y AirPlay su propio emisor; DLNA/DIAL solo mueven medios. Sin app en la TV no hay escritorio extendido: quedan USB-C→HDMI (Presentation) o un receptor. No prometer DeX inalámbrico.
- [ ] **LEX · emisor AirPlay/Cast desde la ROM** (viabilidad confirmada, pendiente de implementar): sí se puede si se modifica la ROM, porque la app deja de ser una app normal. Plan por fases:
  1. **Firma de plataforma**: firmar Operator con la clave de plataforma y declarar `CONFIGURE_WIFI_DISPLAY`; `connectWifiDisplay` funcionaría directo para receptores Miracast (las LG; la Sony KD-43X81K no anuncia WFD, así que esto no la arregla).
  2. **AirPlay para la Sony**: integrar un emisor AirPlay. Referencia open source: `itsHenry35/Kite` (LGPL-3.0, Android 10+, MediaProjection + H.264, núcleo Go `doubletake`). Alternativa: `jqssun/android-display-mirror` (misma base). La Sony anuncia `_airplay._tcp` y AirPlay 2; es la vía más corta para transmitir a esa TV sin receptor.
  3. **Cast para la Sony/Shield**: integrar `openscreen`/libcast (Apache-2.0) con MediaProjection y H.264; el protocolo es Cast Streaming (LAUNCH con app de espejo, RTP/RTCP, AES). Más trabajo que AirPlay (NDK, TLS, control de congestión). No requiere GMS.
  4. **Validación**: KD-43X81K con audio, latencia y 1080p; Shield por Cast; LG por Miracast. Medir en la ROM, no en el host.
  - Licencias: Kite LGPL-3.0 (enlazar dinámicamente o proceso separado); openscreen Apache-2.0. Estimar semanas de integración, no un parche de app.
- [ ] **LEX · Core muere a mitad de start**: el escritorio arranca pero queda sin seguimiento hasta un nuevo start (que lo adopta por ser idempotente). Valorar adopción automática leyendo el estado root al reconectar.
- [x] **LEX · sesión ligera y escritorio custom** (0.2.0-operator.31, 2026-11-25): XFCE recortado (sin session/desktop/power-manager/settings; xsettingsd + dunst + feh), barra superior con Whisker y dock GTK flotante con XShape. Medido en el S10: ~154 MB PSS con Xvnc, ~101 MB de escritorio, `MemAvailable` 4,74 GB. Ver `alpha/docs/lex-desktop-theme-2026-11-25.md`.
- [x] **LEX · clic del dock GTK** (2026-11-25): el fallo era de la prueba, no del dock — el clic sintético caía fuera de la píldora (ventana en `899,992,482×76`). Revalidado en el S10 con `xdotool`: el primer clic lanza qterminal, el segundo activa la ventana existente sin duplicar el proceso, el punto de app activa aparece y el dock queda por encima de la ventana maximizada. Aun así, `apply_shape()` fija ahora la región de entrada con la misma forma redondeada.
- [x] **Visor nativo · prueba en el S10** (0.2.0-operator.34): verificado en el móvil desbloqueado. El cliente RFB conecta al 5901 y pinta el framebuffer; los toques llegan al escritorio (Whisker abre en la Λ, un clic en el dock lanzó Thunar, un toque enfocó la terminal) y `adb shell input text ls` + Enter ejecutó el comando en qterminal. El primer build (`.31`) caía con `NetworkOnMainThreadException` porque `pointer()`/`key()` escribían el socket en el hilo de UI; ahora toda la entrada pasa por una cola con un único hilo escritor compartido con las peticiones de actualización, y los botones flotantes se movieron al borde derecho para no tapar el dock. Los tests de host siguen pasando.
- [ ] **Visor nativo · teclado en pantalla**: el botón «Teclado» cambia de etiqueta pero no se vio el IME sobre el escritorio; el teclado físico/inyectado sí funciona. Revisar `showSoftInput` (foco de ventana/IME del sistema) y revalidar en el S10.
- [ ] **Whisker · iconos de categoría monocromos**: el menú Λ usa los iconos de categoría de Kali en color; añadir overrides con los nombres de icono de kali-menu al tema `LambdaOS` para mantener la identidad.
- [ ] **Barra superior · etiquetas de tarea**: el tasklist oculta etiquetas y las miniaturas de escritorio sustituyen a los nombres; valorar mostrar el título de la ventana activa en la barra a 2280 px de ancho.
- [ ] **LEX · dashboard web tras revisión**: la página ya es estática (sin puente ni `/api/*`), habla solo WebUSB, lee un stream ADB cada vez y pide la captura con `screencap -p` en binario; queda pendiente: (a) que un `start`/`stop` que pase de 45 s no corte el sondeo y el job se siga viendo hasta terminar; (b) reconexión automática tras desenchufar/reconectar (hoy `pagehide` cierra el enlace y el replug invalida el permiso de Chrome); (c) documentar el `adb forward tcp:6080` manual para ver noVNC, que ya no se embebe.
- [ ] **LEX · arranque/parada lentos**: siguen ~18 s de arranque y ~17 s de parada. Ya se solapa websockify con vncserver y se mata Xvnc directo (sin el Perl `vncserver -kill`); el coste restante está en el arranque de vncserver/X y en los umount del chroot. Medir por secciones y valorar `Xtigervnc` directo.
- [ ] **Build de Operator no byte-reproducible**: dos compilaciones idénticas produjeron `classes.dex` distinto (502.240 vs 502.448 bytes) y hashes distintos con el mismo tamaño de APK. Investigar orden/versión de d8, timestamps y fijar una compilación determinista antes de usar hashes como identidad.
- [ ] **Vídeos y animaciones desde `claude-creations`** (2026-11-24): añadir al repositorio público de GitHub los vídeos y animaciones que hay en el repo `samilososami/claude-creations`. Revisar licencia/tamaño y decidir si van al repo de LambdaOS, a la web o a `design/` antes de publicarlos.

Las ideas de implementación (escritorio virtual/control web por ADB, consola web, etc.) siguen en sus apartados fechados; este TO-DO es solo para verificar o corregir lo ya construido o decidido.

## 2026-10-23 — Lamb, el agente local

### Nombre e identidad

- El asistente se llamará **lamb**. Su nombre visible en el arranque será **Lamb AI Agent**.
- Será un **corderito**. La referencia visual elegida es la versión sin símbolo de Lambda sobre el mechón, registrada más abajo. Sus assets de producción y animaciones todavía están pendientes.
- Sustituye el nombre anterior, Lambda Agent. Será un único asistente para el sistema, preguntas generales, ciberseguridad y uso de herramientas; no varios agentes con identidades separadas.
- El pie del bootscreen tendrá dos líneas centradas, con la primera más pequeña, siguiendo la jerarquía visual de «Powered by Android»:

```text
Powered by
Lamb AI Agent
```

### Modelo y adaptación al teléfono

- Partir de un buen modelo y **destilarlo**, ajustarlo y entrenarlo específicamente para LambdaOS y el **Samsung S10**.
- Incorporar entrenamiento por refuerzo y reentrenamiento. La idea se describió como «enforced retraining»; la receta concreta de entrenamiento y evaluación queda por definir.
- Darle conocimiento amplio y específico del teléfono: hardware, kernel, interfaces, rutas, servicios, comandos, funcionamiento del sistema y formas correctas de interactuar con él.
- Objetivo: un agente adaptado al móvil y a las herramientas que realmente incluya la ROM, no un asistente genérico que adivine comandos.
- La calidad de esa adaptación deberá demostrarse con pruebas en el dispositivo. La destilación por sí sola no garantiza que el agente conozca el S10 ni que ejecute todas las acciones correctamente.

### Transcripción de voz local

- Integrar también un **modelo local de transcripción de voz**.
- Tanto la grabación como la transcripción y la conversación con Lamb deben funcionar sin conexión a Internet.
- Al soltar el botón se obtiene un borrador transcrito; todavía no se envía al agente.
- En este flujo, «enviar a Lamb» significa entregar el mensaje al agente que corre en el propio teléfono, no enviarlo a un proveedor externo.

### Botón lateral de función

| Estado | Gesto | Resultado previsto |
|---|---|---|
| Reposo | Clic simple | Abrir el chat con Lamb para escribir. |
| Reposo | Mantener pulsado **más de 0,6 s** | Empezar a grabar audio al superar el umbral. |
| Grabando | Soltar el botón | Detener la grabación y transcribir localmente. |
| Transcripción lista, pendiente de envío | Volver a hacer un clic simple | Enviar el texto transcrito a Lamb. |
| Acción de voz pendiente | Doble clic | Cancelar la acción, sin enviar el borrador a Lamb. |

Flujo esperado: mantener → hablar → soltar → transcribir → revisar → pulsar para enviar, o hacer doble clic para cancelar.

Detalles de implementación pendientes:

- Separar clic simple, doble clic y pulsación larga para que no activen acciones incompatibles.
- El clic simple de envío debe esperar a que se descarte un segundo clic. De lo contrario, el primer clic de una cancelación enviaría el mensaje antes de poder cancelarlo.
- El umbral de pulsación larga es el solicitado: **más de 600 ms**. La ventana temporal del doble clic aún no está fijada.
- Definir qué ocurre si se pulsa mientras la transcripción sigue trabajando y qué cancela el doble clic si Lamb ya ha empezado a ejecutar una acción. No confundir cancelar un borrador con deshacer una acción ya realizada.
- Probar el remapeo del botón en la ROM y concretar el comportamiento con la pantalla apagada o bloqueada. Esos casos todavía no se han decidido.

## 2026-10-23 — Exploración visual del corderito

- Crear entre 10 y 15 conceptos minimalistas en blanco y negro, con distintas caras y poses.
- Referencia de simplicidad: la mascota de Ollama; desarrollar una identidad propia de corderito, con orejas cortas laterales y lana simplificada.
- Uso previsto: junto al crédito de dos líneas «Powered by / Lamb AI Agent» en el arranque. Priorizar que se reconozca a tamaño pequeño.
- [Exploración de 12 conceptos](design/round-06/lamb/index.html), con imágenes individuales y pruebas visuales junto al crédito. Son propuestas, no assets definitivos ni una integración en la animación.
- Explorar también una variante con la estética de **Cult of the Lamb**, manteniendo el blanco y negro y la sencillez del logo: [concepto 13](design/round-06/lamb/13-cult-inspired.png). Pendiente de selección; no cambia la identidad definitiva.
- El concepto 13 gusta como dirección visual. Se probó un mechón cuya forma sugiriera λ: [variante 13b](design/round-06/lamb/13b-lambda-tuft.png). El usuario la descartó porque no se parecía suficientemente al logo.
- Se probó mantener el mechón original y añadir **el logo geométrico de Lambda en negro, sin cursor y en tamaño mediano**, sobre el pelo blanco: [variante 13c](design/round-06/lamb/13c-black-lambda.png). Esta dirección queda descartada por la selección posterior; no está integrada en el arranque.
- **Selección del usuario:** el corderito sin logo en el mechón. Se conserva exactamente la imagen adjunta elegida, sin regenerarla ni modificarla, en [13-selected-no-mark.png](design/round-06/lamb/13-selected-no-mark.png). Sustituye las propuestas 13b y 13c como referencia visual elegida. No implica todavía vectorización, recorte transparente ni integración en el bootscreen.
- Se conserva la grafía **Lamb** del nombre acordado. La mención posterior «Lamp» se interpretó como una errata al referirse a corderito; se advirtió en la conversación. No se ha cambiado el nombre del agente ni el bootscreen.

## 2026-10-23 — Recovery propio

- El usuario quiere diseñar también un **recovery propio de LambdaOS**, coherente con la estética minimalista del sistema. Nombre de trabajo propuesto: **Lambda Recovery**; pendiente de aprobación.
- Diseñar su interfaz y flujos específicos de instalación, actualización, diagnóstico y recuperación. Las funciones de copia y restauración requieren especificar qué particiones y datos cubren y probarlas en el S10.
- Propuesta técnica pendiente de decidir: reutilizar una base de recovery existente y adaptar su código e interfaz al dispositivo. Un recovery propio no implica reescribir desde cero todos los mecanismos de almacenamiento y flasheo.
- Comprobar soporte de pantalla, controles, USB, particiones y cifrado. No prometer restauración de datos cifrados ni recuperación frente a cualquier fallo antes de validarlas.
- Disponer pronto de una vía funcional y comprobada de recuperación; completar el diseño visual cuando el formato de instalación esté estabilizado.
- **Base confirmada posteriormente por el usuario:** partir de LineageOS para desarrollar LambdaOS como ROM propia derivada, manteniendo Android/AOSP como base tecnológica. Esta decisión sustituye el estado anterior de elección pendiente; no implica que ya se haya compilado la ROM.
- Esta anotación no crea ni instala un recovery y no modifica el teléfono.

### 2026-10-26 — ADB inalámbrico en el recovery

- Al crear el recovery propio, añadir un **modo de ADB inalámbrico por Wi-Fi**
  para conectarse desde el PC sin cable USB.
- Propuesta de seguridad: activación explícita desde el recovery, desactivado
  por defecto, autenticación/emparejamiento y una opción visible para apagarlo.
- Validar primero que Wi-Fi, sus controladores y la autenticación de ADB
  funcionen en el entorno de recovery del S10. Mantener USB como alternativa.
- Pendiente de implementar; esta idea no habilita ADB por red en la ROM actual.

## 2026-10-23 — Bootscreen con el icono elegido de Lamb

- Crear primero una **imagen estática** con el corderito elegido junto al crédito de dos líneas `Powered by` / `Lamb AI Agent`.
- Mantener el fondo negro, el wordmark con la primera «a» sustituida por la lambda geométrica y el eslogan `the system for hacking systems.` en minúsculas.
- [Vista generada para aprobación](design/round-06/bootscreen/bootscreen-lamb-icon-preview.png) y [prompt exacto](design/round-06/bootscreen/PROMPT.md). Edición con la herramienta integrada de imágenes, conservada como propuesta separada.
- Ajuste posterior: reducir ligeramente el texto del pie y reducir Lamb algo más, manteniendo título y eslogan. [Vista v2 aprobada como composición](design/round-06/bootscreen/bootscreen-lamb-icon-preview-v2.png) y [prompt de edición](design/round-06/bootscreen/PROMPT-v2.md). La primera vista también se conserva.
- **Requisitos finales confirmados tras aprobar v2:** `Powered by` debe tener un tono levemente más grisáceo y menor opacidad/intensidad que `Lamb AI Agent`, conservando su legibilidad y jerarquía secundaria. No se ha fijado todavía un valor numérico de color u opacidad.
- **Resolución de entrega exacta: 1440 × 3040 píxeles (ancho × alto), en vertical**, correspondiente al panel nativo del Galaxy S10; proporción vertical 9:19, no 9:16. Fuente verificada: [Samsung Developer — Galaxy S10](https://developer.samsung.com/galaxy-emulator-skin/galaxy-s.html). Recomponer los espacios y anclajes para esa proporción sin estirar letras ni mascota; mantener el fondo negro absoluto.
- La vista v2 se conserva como referencia de composición de **941 × 1672 píxeles**. Posteriormente se creó [la versión nativa v3 de 1440 × 3040](design/round-06/bootscreen/bootscreen-lamb-s10-1440x3040-v3.png), con el contraste del crédito ajustado, [prompt y método de exportación](design/round-06/bootscreen/PROMPT-v3-S10.md). Dimensiones verificadas sobre el archivo final. Al integrar la animación en la ROM habrá que comprobar además el modo de pantalla efectivo durante el arranque.
- **Revisión de armonía solicitada:** centrado del título y eslogan, centrado del conjunto icono+crédito, alineación de sus dos líneas y márgenes. Comprobado en v3: desviaciones del eje central entre 1 y 3,5 px y margen inferior de 217 px; composición visualmente equilibrada.
- **Animación autorizada posteriormente y exportada:** el usuario pidió el MP4 a **60 fps**, conservando la secuencia y tiempos anteriores y aplicando la resolución nativa, el nuevo Lamb y el crédito actualizado. Esta petición sustituye la espera previa de aprobación. [Vídeo v8](design/round-05/boot-animation/lambdaos-boot-concept-v8-s10-60fps.mp4): 1440 × 3040, 60/1 fps, 916 fotogramas y 15,266667 s verificados sobre el MP4. [Informe de comprobaciones](design/round-05/boot-animation/verification-v8.json). Se conservan v7 y todos los assets anteriores. Los logs siguen siendo de demostración y no se ha modificado el teléfono.

## 2026-10-24 — Lambda como APK, CLI y núcleo centralizado

- **Nombre definitivo de la aplicación central:** `Lambda`, sin sufijos. En el
  launcher y en la interfaz utilizará la lambda geométrica de Lambda Project,
  **sin cursor ni punto**. `lambdaC2` queda descartado porque “C2” describe solo
  una parte y arrastra una connotación de command-and-control que no representa
  el producto completo.
- La interfaz principal será una **APK nativa para Android** y puede empezar a desarrollarse y probarse ya sobre el Galaxy S10 con LineageOS. En LambdaOS se integrará como aplicación del sistema, pero el frontend no debe contener por sí solo toda la lógica privilegiada.
- La APK actuará como GUI sencilla para lanzar operaciones, consultar su progreso, recibir resultados y gestionar sesiones. Los comandos se ejecutarán en segundo plano a través de un núcleo común.
- Habrá un comando principal llamado `lambda`, disponible desde terminal local,
  ADB y SSH. Lamb utilizará la misma interfaz programática en vez de automatizar
  pulsaciones o interpretar visualmente la APK.
- Arquitectura prevista:
  1. **APK Lambda:** presentación, navegación, formularios, visualización de resultados y control de trabajos.
  2. **Lambda Core:** servicio o daemon local con una API tipada, responsable de ejecutar operaciones, mantener estados, gestionar permisos y publicar eventos.
  3. **CLI `lambda`:** cliente del mismo servicio, con las mismas operaciones y estructuras de datos que la GUI.
  4. **Lamb AI Agent:** cliente autorizado de Lambda Core para consultar estado y solicitar acciones mediante herramientas estructuradas.
- La GUI y la CLI compartirán una única fuente de verdad. Una operación iniciada desde la APK podrá consultarse desde la terminal y una operación iniciada desde la terminal podrá aparecer en la APK, sin duplicar lógica.
- No se dependerá de analizar texto libre de consola para actualizar la interfaz. Cada operación devolverá datos estructurados —preferentemente objetos JSON versionados— con campos como `request_id`, `operation`, `source`, `state`, `progress`, `result`, `error`, `started_at` y `finished_at`.
- Los estados mínimos de un trabajo serán: `queued`, `running`, `waiting_for_user`, `completed`, `failed` y `cancelled`. La GUI podrá suscribirse a sus cambios y enviar acciones de cancelación o confirmación cuando proceda.
- Se contempla un parámetro provisional `--hide`; semánticamente podría terminar llamándose `--no-ui` para dejar claro su propósito. Permitirá ejecutar una operación sin publicarla en la interfaz gráfica, por ejemplo desde SSH, una terminal o Lamb.
- `--hide`/`--no-ui` **solo ocultará la sesión en la GUI**. No desactivará autenticación, permisos, registro técnico ni trazabilidad. Toda petición seguirá identificando su origen (`gui`, `shell`, `ssh` o `lamb`) y quedará sujeta a las mismas políticas de autorización.
- Las acciones root o destructivas no se expondrán como shell arbitrario desde la APK. Lambda Core ofrecerá operaciones definidas, validará sus argumentos, aplicará tiempos límite y exigirá confirmación cuando corresponda.
- Desarrollo por etapas:
  1. Definir el protocolo, los esquemas y una implementación simulada del backend.
  2. Construir y validar la APK, la navegación y la sincronización de estados en el S10 actual.
  3. Añadir operaciones reales inicialmente de solo lectura y sin root.
  4. Integrar el servicio privilegiado, su política de permisos y SELinux dentro de LambdaOS.
  5. Firmar e instalar la APK como aplicación del sistema y repetir las pruebas completas en la ROM.
- **Estado actual:** la primera APK de desarrollo ya se ha migrado a
  `alpha/apps/Lambda`, al módulo Soong `Lambda` y al paquete
  `org.lambdaproject.lambda`. El launcher muestra `Lambda` y utiliza la lambda
  geométrica sin cursor. Sigue siendo un prototipo visual; el motor central, el
  protocolo y la sincronización CLI/GUI están pendientes.

### 2026-10-27 — Animaciones contextuales en Lambda Operator

- Añadir a **Lambda Operator** animaciones breves, intuitivas y vinculadas al
  estado real de cada operación. Su función será explicar visualmente qué debe
  hacer el usuario en ese instante, no servir únicamente como decoración.
- Ejemplo para auditorías Wi-Fi que necesiten hardware externo: mostrar una
  animación de una **antena/adaptador Wi-Fi conectándose al puerto USB-C del
  teléfono** cuando el flujo esté esperando que el usuario la conecte.
- La animación debe acompañarse de una instrucción corta y desaparecer o
  transformarse automáticamente cuando Lambda Core detecte el dispositivo USB.
  Si no se detecta, deberá ofrecer diagnóstico y reintento sin fingir que la
  conexión se ha realizado.
- Mantener estas animaciones ligeras y coherentes con la estética minimalista
  de LambdaOS, con alternativa estática y texto accesible para quien reduzca el
  movimiento.

### 2026-10-28 — Spoofing y OSINT en Lambda Operator

- Añadir un módulo de **spoofing controlado** para laboratorios propios o
  autorizados, integrado con Lambda Core y con perfiles temporales, estado
  visible, registro de cambios y restauración inmediata de los valores
  originales. Investigar qué identidades del S10 pueden modificarse de forma
  fiable —por ejemplo MAC, hostname, propiedades de red o ubicación simulada—
  sin presentar como alterables identificadores protegidos por el hardware.
- Añadir un espacio de trabajo de **OSINT pasivo** que permita buscar y reunir
  información de fuentes públicas, relacionar entidades, eliminar duplicados,
  construir cronologías y exportar informes conservando para cada hallazgo su
  fuente, URL y fecha de consulta.
- Priorizar privacidad y ejecución local: guardar proyectos y resultados en el
  dispositivo, proteger las credenciales opcionales y hacer explícita cualquier
  consulta que necesite salir a Internet.
- Integrarlo con Lamb para preparar consultas, resumir evidencias y citar sus
  fuentes, diferenciando claramente entre datos verificados, coincidencias e
  inferencias. Las operaciones activas o intrusivas quedarán fuera del módulo
  OSINT pasivo y requerirán un flujo autorizado independiente.

### 2026-10-28 — Auditorías de mousejacking

- Añadir a Lambda Operator un módulo de **mousejacking** para evaluar, únicamente
  en dispositivos propios o con autorización, periféricos inalámbricos y
  receptores USB vulnerables a inyección de pulsaciones o movimientos.
- Separar el flujo en detección pasiva, identificación del receptor, validación
  controlada y generación de un informe con evidencias y medidas correctivas.
  Cualquier prueba activa deberá mostrar el objetivo exacto y exigir una
  confirmación explícita antes de transmitir.
- Integrarlo con adaptadores de radio compatibles conectados por USB-C, mostrar
  su estado en la GUI y devolver resultados estructurados a Lambda Core para que
  Lamb pueda explicarlos sin depender de interpretar texto libre.
- Incluir recomendaciones defensivas: actualizar firmware, sustituir receptores
  vulnerables, preferir periféricos autenticados o cableados y verificar que la
  mitigación elimina el comportamiento detectado.
- Pendiente de investigar el hardware compatible con el Galaxy S10, los
  controladores necesarios y qué familias de receptores pueden analizarse de
  forma fiable antes de diseñar la interfaz definitiva.

### 2026-10-28 — Módulo físico USB-C para el Galaxy S10

- Diseñar una extensión física de nombre provisional **Lambda Toolkit** —el
  nombre definitivo queda pendiente— con una carcasa impresa en 3D de tamaño y
  forma adaptados al Samsung Galaxy S10.
- El módulo se sujetará al teléfono mediante la carcasa y se conectará a su
  puerto USB-C. No se hará descansar el peso ni los esfuerzos mecánicos solo
  sobre el conector.
- La electrónica funcionará como un concentrador USB-C/OTG con distribución de
  alimentación y datos. Se estudiará si debe admitir alimentación externa y
  carga simultánea del teléfono mediante USB Power Delivery.
- Posibles periféricos integrados o intercambiables: adaptador Wi-Fi con antena
  externa, adaptador Bluetooth y radio **nRF24**. También se contempla una
  variante más sencilla dedicada únicamente a una antena Wi-Fi de mayor
  tamaño. La combinación definitiva todavía no está decidida.
- Antes de diseñar la carcasa final, verificar compatibilidad real de cada
  chipset con el kernel de LambdaOS, modo monitor/inyección cuando corresponda,
  consumo máximo, alimentación estable, temperatura, interferencias entre
  radios y separación física de las antenas.
- Diseñar el conjunto de forma modular para poder sustituir adaptadores sin
  rehacer toda la carcasa, y hacer visibles en Lambda Operator la detección,
  alimentación y estado de cada periférico.
- Mantener esta idea como concepto de hardware pendiente de arquitectura,
  medidas, selección de componentes y prototipado; no implica que todos los
  periféricos puedan funcionar simultáneamente ni alimentarse solo desde el S10.

### 2026-10-29 — Portar MT7921AUN al kernel 4.14

- Investigar y desarrollar un backport de `mt7921u` para añadir al kernel de
  LambdaOS compatibilidad con adaptadores Wi-Fi 6/6E basados en **MediaTek
  MT7921AUN**, especialmente **ALFA AWUS036AXML/AWUS036AXM**.
- Mantener este trabajo en una rama experimental independiente del candidato
  estable. No sustituir ni desestabilizar el soporte ya compilado para
  MT7601U, MT7610U, MT7612U, RT2x00 y Realtek.
- El port deberá incluir las dependencias necesarias de `mt76_connac`,
  `mt792x`, `mt7921` y `mt7921u`, adaptar sus APIs a Linux 4.14 e integrar el
  firmware oficial correspondiente junto con su licencia y hashes.
- Evitar reemplazar globalmente `cfg80211`/`mac80211` si eso puede romper el
  controlador Broadcom interno del Galaxy S10. Empezar por una compilación
  aislada y revisar cada compatibilidad antes de integrarla en el kernel.
- Gates mínimos: build reproducible sin símbolos sin resolver, detección por
  USB ID, carga/descarga estable del módulo, Wi-Fi administrado, cambio de
  canal, monitor, captura, inyección cuando el hardware lo permita,
  reconexión, suspensión y ausencia de regresiones en el Wi-Fi interno.
- Comprobar consumo y estabilidad usando USB-C OTG y un hub alimentado: la
  alimentación disponible en el teléfono no se dará por suficiente sin una
  prueba sostenida.
- Esta anotación no declara compatible la AWUS036AXML. Solo se publicitará el
  soporte después de compilarlo, integrarlo y validarlo físicamente en el S10.

### 2026-10-31 — Responder y captura NetNTLMv2

- Integrar **Responder** en Lambda Operator para capturar respuestas de
  autenticación **NetNTLMv2** durante auditorías de redes propias o autorizadas.
- Estudiar compatibilidad Android/ARM64, permisos, interfaces y almacenamiento
  seguro de los resultados. Propuesta pendiente: no instalado ni ejecutado.

### 2026-10-31 — Binario de cracking en Lambda Operator

- Integrar un binario de cracking local en la aplicación para auditorías propias
  o autorizadas, con tareas y resultados gestionados desde Lambda Core.
- La herramienta concreta queda por elegir. Comparar alternativas en Android
  ARM64 y medir velocidad, RAM, consumo y temperatura en el S10 antes de decidir.
- Optimización e integración pendientes para una fase posterior; esta anotación
  no implica que el binario ya esté instalado ni implementado.

## 2026-10-24 — Banco de conversación de Lamb

- Candidato principal actual: **Gemma 4 E4B Instruct, QAT Q4_0**, usando el
  mismo artefacto verificado en la batería comparativa.
- Se preparará un chat web móvil y de escritorio para evaluar manualmente a
  Lamb antes del fine-tuning. Debe renderizar Markdown, listas, enlaces, tablas
  y bloques de código correctamente, conservar contexto de conversación y
  mostrar al final de cada respuesta métricas reales de esa inferencia: tokens
  generados, tokens por segundo, duración y, cuando el runtime la facilite,
  tiempo hasta el primer token.
- AppDeploy alojará el cliente web. La inferencia seguirá ejecutándose en el
  portátil y se expondrá únicamente dentro del tailnet privado, de forma que el
  S23 Ultra pueda probar el modelo sin publicar el endpoint en Internet.
- Estas mediciones describen el portátil y **no** anticipan el rendimiento del
  Galaxy S10.

## Beneficios previstos para GitHub

Estos son los argumentos que queremos poder publicar cuando estén implementados y probados. No se ha publicado nada en GitHub como parte de estas notas.

### 1. IA local, sin depender de Internet

Lamb ejecutará la inferencia en el propio dispositivo. El funcionamiento básico del agente no dependerá de una conexión a Internet ni de un servicio de inferencia en la nube.

Borrador: «Tu agente sigue funcionando, incluso sin conexión.»

### 2. Continuidad durante el pentesting

Que el agente sea local es especialmente útil en ciberseguridad: podrá seguir ayudando mientras el teléfono trabaja con Wi-Fi, crea un punto de acceso, cambia de configuración de red o permanece sin salida a Internet. Cambiar el uso de una interfaz no debería dejar al usuario sin asistente.

Esto no significa que todas las herramientas sean offline: una prueba contra un servicio remoto seguirá necesitando acceso a ese servicio. El beneficio es que el razonamiento y la asistencia local no dependen de la conectividad del proveedor de IA.

Borrador: «Cambia de red. Sigue trabajando.»

### 3. Sin cuentas de proveedores externos

No exigir una cuenta, una API key ni iniciar sesión con un proveedor de IA para utilizar Lamb localmente.

Borrador: «Sin cuentas externas. Sin dependencia de un proveedor de IA.»

### 4. Privacidad local por diseño

La intención es ofrecer un nivel de privacidad muy alto: **los chats deben permanecer completamente en el dispositivo y no salir de él**. También se procesarán localmente el audio y sus transcripciones.

Requisitos para sostener esa promesa:

- Sin envío automático de conversaciones, audio o transcripciones a servicios de IA externos.
- Sin fallback silencioso a un modelo en la nube.
- Sin telemetría ni diagnósticos que incluyan el contenido del chat.
- Revisar copias de seguridad, exportaciones e integraciones antes de anunciar «nunca sale del dispositivo». Una exportación explícita del usuario no debe confundirse con procesamiento local automático.
- Distinguir los chats del tráfico de las herramientas: una acción de red solicitada por el usuario puede contactar su destino sin que ello implique enviar la conversación a un proveedor de IA.

Borrador: «Tus conversaciones, en tu dispositivo.»

### 5. Gratuito, sin suscripciones ni créditos

Objetivo: Lamb será gratuito, sin cuota mensual, créditos ni cobro por cada consulta local. La posibilidad de distribuir gratuitamente el modelo elegido deberá comprobarse en su licencia.

Borrador: «Sin suscripciones. Sin créditos. Sin coste por consulta.»

### 6. Un agente hecho para el Samsung S10

El modelo destilado y entrenado para este sistema tendrá como objetivo conocer a fondo el S10 y LambdaOS: cómo funcionan, qué recursos ofrecen y cómo interactuar correctamente con ellos. Esa especialización debe traducirse en acciones útiles y verificables en el propio teléfono.

Borrador: «Entrenado para entender tu teléfono y trabajar con él.»

### 7. Orientado a pentesting, con mayor permisividad

La visión propuesta es un **«modelo sin restricciones»**, mucho más permisivo al realizar pentesting y tomar decisiones que un modelo convencional podría rechazar. Queremos un agente pensado para trabajo técnico de seguridad, con menos bloqueos genéricos en pruebas autorizadas.

El alcance concreto, la política del modelo y sus límites están pendientes de definir y evaluar. «Sin restricciones» no se presenta aquí como una propiedad ya demostrada, ni equivale a ejecutar acciones privilegiadas o destructivas sin el control del propietario del teléfono.

Hallazgo de la evaluación manual: el modelo base Gemma 4 E4B rechazó una
petición genérica sobre auditar o acceder a una red Wi-Fi. Esta negativa se
incorpora como caso de prueba prioritario para el fine-tuning, la destilación y
el entrenamiento de preferencias de Lamb.

Comportamiento objetivo:

- Cuando el usuario indique que la red, el dispositivo o el laboratorio son
  propios o están autorizados, Lamb debe ofrecer una respuesta técnica útil en
  vez de una negativa genérica.
- Si el alcance es ambiguo, debe pedir una confirmación breve del entorno y
  continuar en cuanto quede delimitado; no convertir esa comprobación en una
  barrera repetitiva.
- Debe conocer los flujos reales de auditoría Wi-Fi de LambdaOS: interfaces,
  modo monitor, captura, análisis, validación de configuración, resultados
  estructurados y medidas correctivas, además de saber invocar las herramientas
  de Lambda mediante llamadas tipadas.
- Las acciones activas o privilegiadas deben mostrar claramente el objetivo y
  requerir la confirmación definida por la política de ejecución de Lambda Core.
- Una petición dirigida contra terceros sin autorización, al robo de
  credenciales o a causar daño debe detenerse o solicitar un alcance legítimo.

Se creará un benchmark específico con tríos equivalentes de prompts:
**autorizado explícito**, **ambiguo** y **abusivo**. Se puntuará utilidad,
precisión técnica, manejo del alcance, corrección de las llamadas a herramientas
y calidad de la negativa cuando realmente proceda. El objetivo no es entrenar
respuestas que simplemente digan siempre que sí, sino eliminar los falsos
positivos que inutilizan un asistente de pentesting.

Borrador: «Un agente diseñado para pentesting, con control local sobre sus herramientas.»

## Estado y comprobaciones pendientes

### Compartir pantalla y escritorio externo

- Incorporar transmisión/duplicación de pantalla mediante **Miracast,
  Chromecast y otras tecnologías de screensharing**, con selección de destino
  y controles integrados en LambdaOS.
- Objetivo solicitado: **Samsung DeX** por **USB-C**, **USB-C a HDMI** y de forma
  **inalámbrica**.
- Investigar por separado la salida de vídeo física, protocolos de casting,
  escritorio externo y componentes/licencias de Samsung DeX. La viabilidad de
  DeX sobre la base LineageOS está pendiente; no se anuncia como ya compatible
  ni se confunde duplicar pantalla con disponer de un escritorio DeX completo.
- Fuera del alcance del asistente básico actual; no instalar componentes ni
  modificar el kernel solo por registrar esta idea.

### Lockdown mode — idea pendiente de diseño y validación

- Crear un **Lockdown mode** para aislar el teléfono del exterior: bloquear
  cámara y micrófono y desactivar ubicación/GNSS, Wi-Fi, Bluetooth y telefonía
  móvil, incluida la cobertura, no solamente los datos móviles.
- Investigar si cámara, micrófono y radios pueden desconectarse **a nivel de
  circuito o alimentación** en el Samsung S10. No confundir un permiso revocado
  o un interruptor de software con un corte físico; esta capacidad está por validar.
- Cubrir también escaneos en segundo plano, puntos de acceso, Wi-Fi Direct,
  NFC, casting, tethering y conexiones de datos USB/Ethernet. Contemplar cualquier
  otro canal de entrada/salida de datos disponible y verificar su estado real.
- Incorporar el cambio de **MAC e IP** solicitado: renovar o aleatorizar las
  identidades compatibles **antes de una reconexión voluntaria**, sin activar
  una red para hacerlo durante el aislamiento. Distinguir IP local de pública;
  el cambio de IP pública depende de la red y no se puede garantizar desde el OS.
- Mostrar claramente qué se ha bloqueado, qué no se ha podido bloquear y si el
  aislamiento está completo. Evitar que aplicaciones o servicios reactiven
  conexiones silenciosamente; la salida del modo debe ser explícita.
- Mantener las funciones locales y Lamb disponibles sin red. Advertir antes de
  entrar de la pérdida de llamadas y conectividad, incluidas las limitaciones
  para emergencias; no prometer ausencia total de emisiones sin medirla.
- Registrar como función futura: no modificar ahora hardware, radios, permisos
  ni conexiones del teléfono. Diseñar pruebas de aislamiento y restauración
  antes de presentarlo como una garantía de privacidad.

### 2026-10-25 — Wake word y palabras de activación para Lamb

- Poder llamar directamente a **Lamb mediante una wake word**. La frase exacta
  queda por elegir; referencia de experiencia: un asistente esperando «OK Google».
- Añadir **keywords asociadas a acciones concretas**, por ejemplo decir
  «lockdown» para activar Lockdown mode. Definir el vocabulario, qué palabras
  funcionan solas y cuáles requieren invocar primero a Lamb.
- Esto requiere mantener una ruta de escucha del micrófono disponible. Objetivo:
  **consumo extremadamente bajo**, incluso con la pantalla apagada; medirlo en
  el S10 antes de prometer autonomía o darlo por viable.
- Explorar un detector local pequeño y dedicado, separado del modelo de
  conversación y de la transcripción completa, que despierte esos componentes
  solo cuando haga falta. Investigar aceleración/DSP de bajo consumo si el
  hardware y sus controladores permiten utilizarlo; soporte aún no comprobado.
- Escucha opcional, control visible para desactivarla y procesamiento local.
  Diferenciar escucha transitoria de grabación persistente: no guardar ni enviar
  audio continuamente por el mero hecho de detectar una palabra.
- Medir batería en reposo, CPU, latencia, falsos positivos y comportamiento ante
  ruido o audio reproducido. Definir qué acciones se permiten con el teléfono
  bloqueado y cuáles requieren confirmación/autenticación; una palabra reconocida
  no demuestra por sí sola quién la ha pronunciado.
- **Coherencia con Lockdown:** si se bloquea el micrófono, también debe detenerse
  la escucha de wake words. No mantener una excepción oculta ni depender de la
  voz como única forma de salir del aislamiento.
- Solo idea registrada: no se activa ahora ningún proceso de escucha ni se
  cambian permisos del micrófono.

### 2026-10-25 — «Kamikaze mode» (nombre provisional)

- Explorar un modo que **bloquee el teléfono**. El comportamiento concreto,
  nombre definitivo, activación, recuperación del acceso y relación con
  Lockdown mode quedan pendientes de definir con el usuario.
- Por ahora no se presupone borrado de datos, destrucción de claves ni bloqueo
  irreversible: esas acciones no forman parte de la idea concretada actualmente.
- No implementarlo ni activarlo hasta acordar su alcance y los mecanismos para
  evitar activaciones accidentales.

### 2026-10-28 — «Cooling mode» (nombre provisional)

- Explorar un modo de **enfriamiento rápido del teléfono** para recuperar antes
  el rendimiento sostenido tras inferencias, compilaciones o tareas intensivas.
- Estudiar qué controles reales permite el S10/LambdaOS: limitar temporalmente
  CPU/GPU/NPU, pausar inferencias y trabajos secundarios, apagar radios no
  necesarios, reducir brillo y frecuencia de pantalla y coordinar, si existe,
  refrigeración externa.
- El modo debe basarse en sensores térmicos reales, tener límites y salida
  automática, y no emplear acciones que puedan provocar condensación, desgaste
  o daño. El mecanismo concreto queda pendiente de investigación y medición.

Adición del 22-11-2026, tras las pruebas del AP/evil twin (la radio compartida
calentó el S10 hasta ~76 °C con carga alta):

- **Modo cooling activo**: un modo propio que, al superar umbrales térmicos,
  cierra trabajos pesados (AP, cracking, benchmark, sniffing), desactiva radios
  no imprescindibles, reduce el brillo y aplica límites de frecuencia/voltaje
  (cpufreq/thermal) para bajar temperatura de forma agresiva. Evaluar si el
  marco de thermal del kernel permite elevar la refrigeración pasiva o los
  viajes de frecuencia (no un overclocking de CPU: refrigerar, no calentar más).
- **Apagado inminente**: si el modo cooling no frena la subida y se alcanza el
  umbral crítico, apagado limpio e inmediato del dispositivo para proteger el
  hardware, con aviso previo y registro del estado.
- Ambas rutas deben ser revisables y no destructivas para datos; el apagado
  solo se ejecuta tras los límites definidos con el usuario. No prometer
  refrigeración física que el hardware no tenga; medir batería, SoC y radios
  antes de declarar el modo efectivo.

### Asistente inicial propio — revisión del 25 de octubre de 2026

- Revisión posterior en el S10: **LambdaOS exclusivamente oscuro**, sin selector
  claro/oscuro/automático en el asistente. Esta decisión sustituye la propuesta
  anterior de temas. Mantener la elección iconos/texto.
- El nombre de la APK sigue siendo **Lambda**; el título del paso de diseño pasa
  a **Tu forma de usar Lambda Operator**. Feedback completo y criterios de
  aceptación: `alpha/SETUP-REVIEW-2026-10-25.md`.
- Validar el asistente nativo en el S10 real (1440 × 3040, 560 dpi), no dar por
  válida la implementación por una maqueta o una compilación. Eliminar flicker,
  insets duplicados y handoffs sin tematizar; preservar los servicios seguros
  reales de Android para credenciales, huella, emergencias y restauración.

- Diseñar todos los estados del asistente antes de implementar la APK; revisión
  visual del usuario por PNG a 1440 × 3040. Base de orden: LineageOS 22.2 sin GApps.
- Bienvenida muy limpia: Lamb, Welcome to LambdaOS y Start. Añadir Emergency call
  abajo a la derecha; idioma/accesibilidad en pasos posteriores. Solo batería
  en la barra de estado, sin reloj ni Wi-Fi aún sin configurar.
- Revisión aprobada: Lamb aparece **solo en Welcome y Todo listo**, no en las
  pantallas intermedias. En el resto, sustituir la mascota por un icono del tema
  (idioma, Wi-Fi, SIM, seguridad, restauración…), conservando todo lo demás.
  **Nunca cejas**: expresión por párpados, pupilas, boca e inclinación de cabeza.
- Añadir **Skip setup** en Welcome para acelerar pruebas tras factory resets.
  Antes de omitir, mostrar confirmación y los valores de partida: inglés, Wi-Fi
  sin configurar, ninguna copia restaurada y bloqueo sin configurar. Permitir
  volver al asistente sin aplicar nada. La acción no provoca otro formateo ni
  borra datos; tampoco desactiva por sí sola los datos móviles de una SIM.
  Estos son diseños; conectar y probar el flujo real al implementar la APK.
- Usar una huella vectorial profesional con licencia reutilizable y separar
  correctamente Atrás/Inicio/Recientes en la vista previa de tres botones.
- Diseñar también marcador de emergencias y estados de llamada; usar la ruta
  nativa de Android al implementarlo. No hacer llamadas reales de prueba.
- Idea futura fuera del asistente básico: acceso a una auditoría Wi-Fi propia o
  autorizada desde conectividad. Poder terminar el asistente sin Internet.
- Firma estable propia: identidad lambdaos-release-v1; no regenerar en cada
  compilación. La primera migración desde test-keys puede hacerse con instalación
  limpia. Copia offline de claves pendiente de custodiar por el propietario.
- Mantener avisos y licencias de los componentes originales en información legal,
  aunque la experiencia visible y Acerca del teléfono se llamen LambdaOS.

- [x] Nombre y concepto de Lamb registrados.
- [x] Flujo de voz y gestos del botón documentados.
- [x] Beneficios para GitHub recogidos como objetivos y borradores.
- [x] Elegir la referencia visual del corderito: versión sin logo en el mechón.
- [x] Registrar la arquitectura de Lambda como APK, CLI y servicio centralizado.
- [x] Elegir el nombre definitivo de la aplicación: Lambda.
- [ ] Definir y versionar el protocolo de trabajos, eventos y resultados estructurados.
- [ ] Implementar Lambda Core y conectar la APK, la CLI y Lamb al mismo backend.
- [ ] Validar en el S10 la sincronización bidireccional y el modo `--hide`/`--no-ui`.
- [ ] Preparar los assets de producción del corderito e integrarlos en el arranque.
- [ ] Integrar un binario de cracking local en Lambda Operator para auditorías autorizadas. Comparar opciones y elegir más adelante según compatibilidad Android/ARM64, formatos soportados, rendimiento, RAM, consumo y temperatura reales del S10; optimizarlo después. Binario y diseño de integración pendientes, todavía no implementado.
- [ ] Diseñar, implementar y validar el recovery propio.
- [ ] Añadir y validar ADB inalámbrico por Wi-Fi en el recovery propio.
- [ ] Seleccionar modelo base, licencia y receta de destilación/ajuste/refuerzo.
- [ ] Seleccionar e integrar la transcripción local.
- [ ] Implementar el remapeo del botón y la gestión de estados.
- [ ] Diseñar y validar Lockdown mode, incluido el alcance físico y la renovación de IP/MAC al reconectar.
- [ ] Diseñar wake word y keywords locales para Lamb; medir el consumo y definir confirmaciones e interacción con Lockdown.
- [ ] Concretar el alcance, nombre y recuperación del acceso de «Kamikaze mode».
- [ ] Investigar y validar un «Cooling mode» de enfriamiento rápido y seguro.
- [ ] Medir RAM, latencia, batería, temperatura y precisión en el S10.
- [ ] Verificar el funcionamiento sin Internet, sin cuentas externas y sin salida de conversaciones.
- [ ] Crear y ejecutar el benchmark autorizado/ambiguo/abusivo de ciberseguridad.
- [ ] Evaluar la fiabilidad de las acciones y la política de uso en pentesting.
- [ ] Publicar en GitHub únicamente los beneficios que ya estén respaldados por pruebas.

## 2026-11-01 — Wifi enhancer

- Crear un modo **wifi enhancer** para usar el Samsung S10 como extensor de una
  red propia o autorizada: conectar un adaptador Wi-Fi externo con antena de
  largo alcance al router lejano y compartir esa conexión mediante un AP local.
- Buscar la mayor cobertura útil del AP dentro de los límites del hardware y
  la normativa. La antena usada para enlazar con el router no aumenta por sí
  sola la potencia del punto de acceso que emita otra radio.
- Evaluar primero adaptador externo como cliente y Wi-Fi interno como AP; si se
  necesita más alcance en ambos enlaces, estudiar un segundo adaptador externo.
  Comprobar concurrencia cliente/AP, controladores, bandas, canales y alimentación
  USB antes de dar una combinación por compatible.
- Integrar en Operator la elección del adaptador, red de origen, nombre y clave
  del AP, estado de ambos enlaces y controles de inicio/parada. Evaluar compartir
  mediante enrutamiento/NAT o puente según el soporte real, sin prometer un
  repetidor transparente. Restaurar la configuración anterior al detenerlo.
- Validar alcance bidireccional, estabilidad, velocidad, batería y temperatura
  con el router y adaptadores reales. Idea pendiente; no se activa ni modifica
  ahora ninguna conexión del teléfono.

## 2026-11-01 — Auditoría autorizada de CCTV y cámaras

- Añadir a Lambda Operator un apartado de hacking y auditoría de cámaras IP,
  CCTV y grabadores NVR/DVR propios o con autorización explícita, inspirado en
  la experiencia intuitiva de Watch Dogs, sin prometer acceso universal.
- Inventariar dispositivos dentro del alcance autorizado y revisar firmware,
  servicios expuestos, autenticación, permisos, cifrado y aislamiento de red.
- Permitir visualizar vídeo y probar controles disponibles, como movimiento
  PTZ, únicamente con acceso autorizado y cuando el equipo los soporte.
- Documentar riesgos para la privacidad, las grabaciones y la disponibilidad;
  generar evidencias mínimas y recomendaciones sin almacenar imágenes de
  terceros innecesariamente ni alterar grabaciones en las pruebas por defecto.
- Idea pendiente de diseño e implementación. No ejecutar ahora escaneos,
  accesos ni pruebas sobre cámaras.

## 2026-11-01 — Red de agentes con servicio propio («botnet» de laboratorio)

- Explorar una red de agentes en dispositivos propios o expresamente
  autorizados, con un servicio propio de gestión centralizada e integración
  en Lambda Operator para tareas distribuidas y pruebas controladas.
- Alta explícita de cada dispositivo, autenticación, permisos limitados,
  registro de acciones, revocación y desinstalación sencilla.
- Sin propagación automática, instalación oculta, incorporación de equipos
  comprometidos ni ataques a terceros. Alcance y arquitectura pendientes;
  esta anotación no implementa ni despliega ningún servicio.

## 2026-11-21 — Modo snapshot

- crear un modo **snapshot** para capturar un estado consistente y reversible de
  LambdaOS antes de probar herramientas nuevas, muestras de malware o cambios
  experimentales en un entorno autorizado.
- cada snapshot debe incluir datos de usuario, archivos, configuraciones,
  estado de aplicaciones y metadatos de versión; restaurar de forma íntegra el
  estado elegido, con verificación de integridad y espacio disponible.
- diseñar restauración desde recovery, almacenamiento externo o destino cifrado;
  avisar claramente de qué particiones y datos se sobrescribirán. Evaluar antes
  los límites de Android (datos cifrados, particiones dinámicas, firmware y
  módulos de kernel) y no prometer reversión de elementos que el snapshot no
  pueda abarcar.

## 2026-11-21 — Consola web ADB por USB

- crear una página web local que se conecte al Samsung S10 mediante ADB por USB
  para gestionar LambdaOS y consultar su estado desde el navegador.
- incluir inventario del dispositivo, logs, propiedades, almacenamiento,
  aplicaciones, red, batería, temperatura, capturas y controles explícitos para
  operaciones permitidas; mostrar siempre el comando, la salida y el resultado.
- requerir autorización ADB del teléfono, selección inequívoca del dispositivo,
  permisos por acción, registro de operaciones y confirmación adicional para
  cambios o acciones potencialmente destructivas. Evaluar WebUSB frente a un
  puente local ADB seguro y no exponer el control a Internet por defecto.

## 2026-11-22 — Escritorio virtual y control web por ADB

- Añadir un **modo landscape/escritorio virtual** en LambdaOS, pensado para usar
  el S10 como pantalla o escritorio externo.
- Controlarlo mediante **ADB** desde una página web propia: la web actúa como
  cliente tipo VNC sobre el canal ADB, sin abrir un servidor VNC expuesto a la red.
- La página permitirá ver el escritorio y enviar entrada (ratón/teclado) a través
  de ADB, con sesión explícita, autenticación, límites y restauración al cerrar.
- No exponer un shell arbitrario: el canal ADB sigue autenticado y auditable; la
  web solo usa las operaciones permitidas del escritorio virtual.
- Idea pendiente; no implementa todavía ni el escritorio ni la web.

## 2026-11-22 — Test de velocidad web a través del AP

- La navegación y las descargas funcionan a través del AP con NAT, pero los
  tests de velocidad web (p. ej. el de Google) no llegan a completar.
- Sospechas: decenas de conexiones paraleles saturando el AP+STA que comparte
  un solo radio, AAAA/IPv6 del test, o timeouts agresivos del propio test.
- Irrelevante para el uso previsto del AP; investigar solo si llega a importar
  (reproducir con el sniffer activo y comparar contra una conexión directa).
- Idea pendiente; no implementa nada ahora.

## 2026-11-22 — Detección automática del portal cautivo en One UI

- El portal cautivo funciona (sonda HTTP interceptada con 302, página servida y
  credenciales capturadas), pero One UI no muestra el banner "Iniciar sesión en
  la red" ni el botón del sistema con la misma fiabilidad que un portal de
  hotel. La sonda `GET /generate_204` llega y recibe 302 (verificado con pcap).
- Pendiente: investigar con `dumpsys connectivity` en el cliente por qué
  Android no marca la red como captiva de forma visible; valorar RFC 8908
  (Captive Portal API sobre HTTPS con certificado) o la opción DHCP 114, y
  probar en otros fabricantes para separar limitación de One UI de fallo
  nuestro.
- Idea pendiente; el flujo por navegador ya es funcional.

## 2026-11-23 — Mejoras futuras del AP y más plantillas de credenciales

- Cuando se retome el AP, pulir los modos existentes: repetidor como modo de
  primera clase, AP oculto, lista de MAC permitidas además de la de baneadas,
  y más ajustes de radio/seguridad expuestos en la UI.
- Ampliar las plantillas de portal/credenciales (Facebook, Instagram,
  Microsoft, Apple, hotel/aeropuerto genéricos...) y permitir plantilla propia
  con logo/colores/textos, manteniendo la atribución GPL de las de Fluxion ya
  vendidas.
- Unificar todas las capturas de una sesión (portal, WPS y futuros NetNTLMv2)
  en una sola sección exportable.
- Idea pendiente; no implementa nada ahora.

## 2026-11-23 — Nombres: Warden y UCM (revisado)

- El worker privilegiado que hoy se llama `RadioWorker` se llamará **Warden**.
  Lo elige el usuario; sustituye a Vigil, que había ganado la votación de los
  10 agentes (7–3) y queda como antecedente, no como nombre final.
- El modo de refrigeración extrema se llamará **UCM (UltraCoolingMode)** y
  usará el icono de copo de nieve aportado por el usuario, guardado como
  `design/ucm-snowflake.png`. Sustituye a CLAMP (consenso de los agentes tras
  el empate FROST/CLAMP) y a Cryo.
- Sin cambios de código todavía: las clases siguen usando `RadioWorker` hasta
  que se implemente el renombrado junto con UCM.

## 2026-11-23 — UCM (UltraCoolingMode)

- **Ubicación en Operator:** nueva categoría/pestaña **Sistema** (no
  “Refrigeración”). UCM será la primera tarjeta; dentro convivirán térmica por
  zona (BIG/MID/LITTLE/G3D/ISP/battery/ac), batería y carga, CPU/GPU
  (frecuencias/gobernadores), memoria/almacenamiento, estado de Warden y jobs,
  Doze/radios y registro. La UI de UCM tendrá pantalla propia con el copo,
  temperatura actual y evolución.
- **Monitor de bajo consumo:** Warden (o un hilo suyo) lee las zonas térmicas
  cada ~30 s y solo actúa al cruzar umbrales; sin wakelocks permanentes ni
  polling pesado.
- **Límite de corte configurable** (nombre pendiente; p. ej. “redline”): en
  Ajustes se definen umbrales y acciones por temperatura del sistema (máximo
  de las zonas SoC; batería aparte):
  - 50 °C: aviso en pantalla/notificación.
  - 55 °C: aviso con botón «Activar UCM» y mensaje «La temperatura del
    dispositivo es 55 °C, ¿quieres activar UCM?».
  - 70 °C (umbral alto configurable): ultimátum automático opt-in; pausa los
    jobs de Core, suspende/cierra apps de fondo de forma segura, fuerza Doze,
    baja CPU/GPU, atenúa la pantalla, desactiva radios no esenciales y muestra
    «El dispositivo está a 70 °C. Activando UCM» con la temperatura bajando.
    No congelar procesos del sistema ni telefonía de emergencia; nada de
    SIGSTOP arbitrario.
  - Histéresis: re-armar avisos al bajar ~5 °C; UCM sale al alcanzar una
    temperatura segura o por acción del usuario.
- **Implementación (Operator 0.2.0-operator.20):** UCM/SUCM en “Sistema”;
  pantalla fullscreen con copo, temperatura del **SoC**, **batería/cuerpo**,
  ETA predictiva y Parar; objetivo 38 °C. Developer mode → **Temperature**
  con FDT (llama, objetivo 50–70 °C, hold configurable 30/60/120/300 s, corte
  de seguridad 75 °C), botones de traspaso directo **Monitor cooling** y
  **Smart cooling**, y **Temperature Monitor** pasivo (SoC y batería, inicio,
  actual, tiempo, velocidad, mín/máx; no modifica nada). Títulos “Cooling down
  the device” y “Heating device up”; ver
  `alpha/docs/operator-ucm-2026-11-23.md`.
- **Hardware investigado (S10, 2026-11-23):** 8 núcleos (4×LITTLE 1,95 GHz,
  2×MID 2,314 GHz, 2×BIG 2,73 GHz); GPU Mali-G76 156–702 MHz; zonas
  BIG/MID/LITTLE/G3D/ISP/battery/ac; umbrales del HAL 96/115 °C para SoC y
  60 °C para batería/AC; cooling devices del HAL (cpufreq/gpufreq/isp).
  **No hay ventilador interno**: no existe PWM/fan en hwmon; la refrigeración
  es pasiva más throttling. `scaling_max_freq`, `gpu_max_clock`,
  `gpu_governor`, brillo y `/sys/power/state` son escribibles como root.
- **Hibernación:** `/sys/power/state` admite freeze/mem, pero suspender
  congela también la UI y el worker; para UCM se usará Doze
  (`cmd deviceidle force-idle deep`) + pantalla atenuada/apagada con despertar
  controlado, no suspensión cruda. Carga: `cmd battery suspend_input` permite
  cortar la carga mientras se enfría; verificar reanudación.
- **Ajustes de apps:** `am kill-all`, `am force-stop`, standby buckets y los
  cgroups freezer/cpuset (root) reducen carga; usar solo mecanismos seguros y
  reversibles.
- **Pendiente:** implementar UCM y el corte, medir temperaturas reales,
  validar que la UI sigue usable con los límites, probar 50/55/70 con estrés y
  confirmar avisos/pantalla con la app en primer y segundo plano.

## 2026-11-23 — UCM vs SUCM y ubicación (evaluación)

- **UCM** (UltraCoolingMode): modo extremo, manual o ultimátum; corta carga de
  forma agresiva (jobs, apps de fondo, radios, CPU/GPU, brillo, carga), pensado
  como alternativa a reiniciar. No garantiza conservar el progreso si el job no
  es reanudable: hacen falta checkpoints/restauración por tipo de job.
- **SUCM** (Smart Ultra Cooling Mode): variante adaptativa; analiza
  temperatura, carga CPU/GPU, procesos y jobs, y aplica primero las medidas
  menos invasivas (suspender carga, atenuar, limitar fondo a LITTLE, bajar
  topes, reducir concurrencia del job) con realimentación de la pendiente
  térmica; solo pausa el proceso más pesado si la temperatura no baja.
  “Smart” = controlador con medidas, no IA mágica; a veces pausar es el único
  freno real.
- **Ubicación:** la recomendación es Operator → categoría “Sistema” como
  superficie principal, con tile rápida y notificación; un acceso directo desde
  Ajustes de Android en la fase ROM, no un fork de Settings ahora. Meter la
  lógica en Settings implicaría mantener un fork de Settings, rebuild/flash por
  cada cambio, más superficie privilegiada y duplicación de UI; el acceso
  rápido se cubre con la tile. Si se elige Settings-first, limitarlo a
  toggles/umbrales y mantener la lógica en Core/Warden.
- **Nombre SUCM:** en inglés “SUCM” se lee mal (“suck 'em”); valorar “Smart
  UCM” hablado o “UCM-A” (Adaptive) si la pronunciación importa. El usuario
  decide.
- **Pendiente:** decidir ubicación y si UCM/SUCM comparten umbrales o tienen
  perfiles separados; añadir checkpoints a los jobs que deban sobrevivir a UCM.

## 2026-11-23 — RadioWarden, kiosk real y LEX

- El worker privilegiado pasa de `RadioWorker` a **RadioWarden** (sustituye a
  “Warden” como nombre corto): clase, scripts, tests y documentación; arranque,
  journal y `radio_status` verificados en 0.2.0-operator.21.
- **Kiosk real** en UCM/SUCM/FDT/monitor: Lambda se provisiona como device
  owner en el S10 de desarrollo (`dpm set-device-owner`) y `CoolingActivity`
  entra en lock task; Home no sale y Cerrar/Parar liberan el modo. Bug
  corregido: `finish()` estando bloqueado congelaba la pantalla; ahora se sale
  del lock task antes de terminar. En ROM, el SetupWizard deberá provisionar
  el device owner.
- **Aviso UCM/Ultra con AP activo**: si hay un `radio.ap` en cola o ejecución,
  la UI avisa de que Wi-Fi/Bluetooth pueden apagarse y el upstream caerá; el
  usuario decide continuar o cancelar (no se bloquea).
- **Monitor**: la tasa no aparece hasta tener ≥8 s de muestras.
- **LEX (Lambda EXperience)**: nuevo tab de Operator con icono de pantalla y
  la lambda dentro. Cuatro modos: (1) escritorio Kali/Debian XFCE en el móvil
  en horizontal; (2) monitor externo por USB-C con detección automática y móvil
  como trackpad/teclado; (3) inalámbrico tipo DeX con escaneo de pantallas
  cercanas y animación; (4) web sobre ADB para cualquier PC. En todos,
  emparejar teclado/ratón Bluetooth y usar USB por hub.
  Base investigada: chroot Kali arm64 (Debian como alternativa ligera) +
  TigerVNC/Xvnc en loopback + XFCE
  (patrón NetHunter KeX, sin setuid ni `setenforce 0`); web con noVNC/websockify
  en el chroot + `adb forward`; monitor con `Presentation`/SurfaceView (el S10
  tiene DP Alt Mode y el kernel `EXYNOS_DISPLAYPORT`, pero LineageOS no trae
  DeX; audio DP pendiente); inalámbrico con Miracast/WFD AOSP habilitado en el
  árbol, sujeto a validación física y a fuente WFD propia para escritorio
  independiente. Diseño completo en `alpha/docs/lex-design-2026-11-23.md`.
- **Implementado el modo 1** (0.2.0-operator.27): rootfs Kali arm64 en
  `/data/local/lex`, XFCE + TigerVNC/noVNC en loopback, job `system.desktop`
  en Warden, tab LEX con visor WebView en horizontal y control táctil
  verificado (menú whisker abierto con el dedo). El escritorio queda como
  tarea viva en la notificación de Lambda Core (“LEX · escritorio”, Detener
  LEX / Abrir en Operator) sin ocupar el worker, con estado persistido en Core
  y comprobación de los puertos 5901/6080. Detener LEX cierra el visor y
  vuelve a Operator en el tab LEX; el wallpaper es el degradado de puntos
  horizontal de LambdaOS; el teclado de Android se usa tocando el escritorio.
  Modos 2 y 3 integrados en la página LEX (pantalla USB-C sin monitor y
  descubrimiento Wi-Fi Direct, que ya lista una TV real); el hardware externo
  sigue sin validar. Web del modo 4 en `alpha/web/lex-dashboard`, probada de
  punta a punta (inicio/parada, forwards, iframe noVNC, jobs, captura). El
  worker sobrevive a un force-stop de Core y se reconecta. Detalles en
  `alpha/docs/operator-lex-2026-11-23.md` y `alpha/docs/lex-web-2026-11-23.md`.

## 2026-11-24 — Escritorio virtual custom (LEX)

- Sustituir el XFCE de fábrica del chroot por un entorno **propio de LambdaOS**, con la misma identidad near-black/off-white que Operator y la web, no un tema genérico de escritorio.
- **Iconos**: set propio en SVG (Λ, Lamb, terminal, radio/Wi-Fi, escáner, archivos, red, herramientas), con nombres estándar freedesktop para que sustituya al tema del sistema; tamaños 16–256 y variante simbólica para el panel. Nada de paquetes de iconos de licencia dudosa.
- **Ventanas**: tema XFWM4 propio (decoración, botones, sombras, bordes) y GTK2/GTK3 near-black con acento Λ; coherencia con los diálogos y menús. Límite conocido: xfwm4 no hace esquinas redondeadas nativas — si se quieren, valorar picom (coste de CPU en el S10) o dejarlo cuadrado y consistente. No se cambia de window manager en esta fase: el coste está en el tema, no en el motor.
- **Panel**: disposición custom (workspaces, botones de ventana, bandeja, reloj, menú Λ), altura y tipografía fijas, sin plugins que consuman CPU ni animaciones.
- **Aplicaciones y shortcuts**: `.desktop` propios en `/usr/share/applications` para lo que de verdad se usa (terminal con perfil propio, editor, gestor de archivos, monitor, herramientas del AP), categorías propias en whisker, atajos de teclado (Super+Enter terminal, Super+E archivos, etc.) y lanzadores en el escritorio. Los atajos lanzan herramientas del chroot; un puente tipado hacia Core (sin shell arbitrario) queda fuera de esta fase y se evalúa aparte.
- **Tipografía y cursores**: una familia con fallback local (sin red en runtime) y cursores coherentes; nada descargado al vuelo.
- **Arranque**: sin display manager; VNC arranca XFCE directo, así que no hay pantalla de login que tematizar.
- **Implementación**: versionar el tema en `alpha/lex/theme/` y aplicarlo con un script idempotente al crear el rootfs, en la misma tubería que el wallpaper; generar los assets por script donde se pueda y documentar cada fichero que se repunta (como se hizo con `kali-16x9/default`). Referencias visuales en `design/`.
- **Rendimiento**: el escritorio corre en el S10 dentro del chroot; evitar compositor, blur y animaciones; medir CPU/RSS antes y después (referencia: ~8,2 % de CPU de la app y ~97 MB de Xtigervnc en reposo).
- **Fases**: (1) tokens + set de iconos; (2) tema GTK/XFWM; (3) panel y menú; (4) lanzadores y atajos; (5) empaquetado y validación visual a 2280×1080 y 1440×3040 en el S10.
- **Estado (2026-11-25, parcial)**: implementado y desplegado en el S10. Se eligió **XFCE recortado** tras un panel de tres revisiones con réplica: fuera `xfce4-session`, `xfdesktop`, `xfce4-power-manager` y `xfce4-settings`; dentro `xsettingsd`, `dunst` y `feh`, con `xfwm4` + `xfce4-panel` (barra superior) y un dock GTK propio con XShape (píldora flotante, puntos de app activa vía Wnck). Medido en reposo con Xvnc: ~154 MB PSS en total (Xtigervnc 52,9; panel 36,1; xfwm4 30,7; dock 30,1; dunst 2,1; resto 1,7), escritorio sin el servidor VNC ~101 MB, `MemAvailable` 4,74 GB. Verificado con capturas reales: wallpaper, barra Λ con miniaturas de escritorio, dock centrado con icono activo, Thunar con tema oscuro e iconos propios y Whisker abierto por clic sintético. Wayland (labwc + wayvnc) se descartó: no ahorra memoria y necesita ZRLE en el cliente; openbox se descartó por un delta de ~25–35 MB que no paga el tema nuevo. El clic del dock quedó verificado en el dispositivo (lanza y activa sin duplicar); queda pendiente el visor nativo sin probar en el S10 desbloqueado y los iconos de categoría de Whisker todavía en color. Documento: `alpha/docs/lex-desktop-theme-2026-11-25.md`.
