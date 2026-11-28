# Lambda Operator v0.2 — validación en el S10

Fecha: 30-10-2026. Primera entrega funcional de Wi-Fi, Bluetooth y USB sobre
Lambda Core. **No es una implementación completa de todas las funciones de
pentesting ni del broker root de C2.** Lamb/IA se mantiene en checkpoint.

## Artefacto e instalación

- Paquete: `org.lambdaproject.lambda`, `0.2.0-operator.1`, versionCode `20`.
- APK: `out/operator-v1/Lambda-operator1.apk` (148.003 bytes, firmado para instalación).
- SHA-256: `92569ae3d74e5205968b9fb0f581c020df43602460bc1ffda786f388a2c673ed`.
- Firma conservada: `lambdaos-release-v1 / releasekey`.
- Certificado SHA-256: `dd4c8fe7ce0cb97d53df455c8f2ddc08ba90759985d6bfa2c9ed91759d12f6e6`.
- Instalada mediante `adb install --no-incremental -r`; mantiene datos y UID.
- Base: Galaxy S10 SM-G973F, `beyond1lte`, Android 15, LambdaOS `0.1.0-alpha.2`,
  kernel `4.14.356-lambdaos+`, SELinux `Enforcing`, `ro.adb.secure=1`.
- Checkpoint anterior: `../checkpoints/operator-before-core-20261030/` conserva
  las fuentes y el APK que venía incluido en la ROM.

El APK actualizado está en `/data/app` sobre el paquete del sistema. Las fuentes
y la inclusión del CLI están preparadas en el staging habitual de la próxima ROM.
**No se ha creado ni flasheado una nueva ROM para esta entrega.** Un factory reset
antes de esa próxima ROM recuperaría el APK antiguo de `/product`.

## Pruebas de la candidata final

Evidencia local: `testing/operator-v1/qa-1793375712668.json`, con el SHA del APK
instalado y recibos completos. Contiene identificadores de redes/dispositivos;
no debe publicarse sin anonimizar. No se han conectado dispositivos ajenos ni
intentado autenticaciones, desautenticaciones, escritura GATT o payloads HID.

**44 comprobaciones de dispositivo/contrato superadas.** Las duraciones de la
tabla son del trabajo en Core, no latencia completa de UI/ADB ni promedios de
un benchmark estadístico. El entorno de radio cambia entre muestras.

| Operación | Resultado observado | Tiempo |
|---|---|---:|
| Escaneo Wi-Fi nuevo | 21 BSSID; broadcast y timestamps nuevos | 2,779 s |
| Caché Wi-Fi | 21 BSSID; identificada expresamente como caché | 23 ms |
| Auditoría del snapshot Wi-Fi | 1 abierto, 2 con protección antigua anunciada, 14 WPS | 13 ms |
| Canales | 9 canales primarios observados | 13 ms |
| Conexión Wi-Fi | Enlace, IP, DNS y rutas leídos | 18 ms |
| Señal Wi-Fi | 3 muestras; media −64 dBm | 3,025 s |
| Escaneo desde segundo plano | 22 BSSID nuevos, sin Activity visible | 3,168 s |
| Estado Bluetooth | Adaptador y capacidades leídos | 22 ms |
| BLE | 13 anunciantes en una ventana de 5 s | 5,081 s |
| Auditoría Bluetooth | Snapshot de 13 dispositivos analizado | 12 ms |
| Classic | 4 dispositivos descubribles en 5 s | 5,062 s |
| Vínculos Bluetooth | 0 vínculos; resultado vacío correcto | 11 ms |
| Inventario USB | 0 periféricos OTG, no confundidos con el cable ADB | 10 ms |
| Auditoría USB | Sin periféricos; estado local de depuración leído | 14 ms |
| Observación USB | Ventana de 3 s, sin eventos de conexión | 3,020 s |
| Capacidades locales | Kernel e interfaces leídos | 17 ms |

Las observaciones de anuncios **no prueban explotabilidad**. WPS anunciado no
equivale a un PIN vulnerable; un SSID con distintos cifrados no demuestra un
evil twin; nombres BLE y MAC aleatorias no identifican inequívocamente hardware.
El mapa de canales cuenta BSSID, no mide utilización del espectro.

### Contrato, permisos y ciclo de vida

Verificado en la tanda final:

- Catálogo de 17 herramientas con tipos, argumentos y efectos.
- Rechazo de herramienta desconocida, campos extra, `caller_uid` falsificado,
  duraciones inválidas, tipos incorrectos y IDs con rutas.
- Idempotencia de la misma petición y rechazo de un ID reutilizado con otra.
- `--hide` fuera de la lista normal, pero conservado en el registro y consulta
  explícita de tareas ocultas.
- Cancelación de tarea activa y de tarea todavía en cola.
- Continuidad al enviar la Activity al launcher.
- `force-stop`/reapertura: trabajos pendientes pasan a `interrupted`, sin
  reejecución; los recibos terminados siguen disponibles.
- Retirada temporal de `BLUETOOTH_SCAN`: fallo `permission_denied`, sin iniciar
  escaneo; permiso restaurado al terminar.
- Wi-Fi con Activity oculta: caché disponible; retirada de
  `ACCESS_BACKGROUND_LOCATION`: `permission_denied`, no lista vacía engañosa;
  permiso restaurado y segundo escaneo nuevo confirmado sin abrir la Activity.
- GATT con dirección no presente en el snapshot y descriptores USB con un
  snapshot de otra familia: rechazados antes de acceder al destino.
- Ningún trabajo activo al terminar y buffer de crashes vacío en la comprobación.

Una APK de pruebas independiente (`org.lambdaproject.operatorprobe`, firma y UID
distintos) supera **29 aserciones**: 7 comprobaciones de rechazo IPC (seis métodos
y query) y 22 de análisis determinista con fixtures. Resultado en
`testing/operator-v1/probe-result-final.txt`. Los fixtures no se presentan como
validación de hardware. La sonda de pruebas se ha desinstalado del teléfono.

### Reinicio completo del teléfono

Repetido con el APK final: ADB vuelve como `device`, `sys.boot_completed=1`;
después del primer desbloqueo, Core arranca automáticamente por
`BOOT_COMPLETED`, sin abrir Operator. El servicio aparece como foreground en
`post-unlock-service-final.txt`. El historial anterior permanece disponible.
Desde terminal y sin Activity visible se obtiene otro escaneo nuevo de 22 BSSID
en 3,233 s, con broadcast y timestamps recientes. Evidencia privada completa:
`testing/operator-v1/postboot-final.json`. No se inicia un escaneo automáticamente
al arrancar: lo inicia la petición explícita de esta prueba.

La primera prueba de reinicio descubrió que el permiso "solo durante el uso"
permitía interpretar un acceso Wi-Fi bloqueado como una caché vacía. Se añadió
la autorización de segundo plano, su explicación en Ajustes y la comprobación
AppOps. La regresión negativa y la recuperación están en la tanda de 44 casos.

### Recursos

- Core en reposo: 6.041 KiB PSS antes y 6.346 KiB después de la tanda final
  (~5,9–6,2 MiB). No incluye la Activity ni representa un máximo. La tanda
  reinicia procesos: no es una medida continua de crecimiento de memoria.
- Muestra puntual durante BLE en la candidata previa con el mismo backend:
  13.131 KiB PSS (~12,8 MiB), no un máximo estadístico.
- Sensor de batería: 28,8 → 29,3 °C durante la tanda final.
- Teléfono conectado a alimentación y al 100 %. **No hay medida válida de
  autonomía o consumo energético** en esta prueba.
- Sin bucle de sondeo ni wake lock mantenido por Core cuando no hay tareas.

## Revisión visual y entrega

Recorrido real por ADB de los menús Wi-Fi/Bluetooth/USB, carga, resultados,
análisis y navegación. Capturas a 1440×3040 en `testing/operator-v1/`.
Paleta monocroma, barra inferior opaca, márgenes seguros, textos legibles,
animación breve de navegación y progreso cancelable. Se corrigieron el icono
de notificación (antes aparecía como un cuadrado), el centrado del progreso y
el regreso desde un resultado a su sección de origen.

Exportación real desde el botón de la interfaz y el selector de documentos de
Android: informe USB vacío guardado en `Documents`, recuperado y parseado como
JSON válido (438 bytes). Evidencia: `testing/operator-v1/export-usb-ui.json`.
No se confundió el resultado sin accesorios con una validación de periféricos.

El servicio no requiere root para estas operaciones. Su proceso es independiente
de la interfaz, pero comparte el UID de la aplicación: no se anuncia como un
broker privilegiado aislado por un nuevo dominio SELinux.

## Pendientes explícitos

- Enumeración GATT frente a un periférico propio/autorizado: código implementado,
  ruta de rechazo probada; conexión exitosa de hardware todavía no validada.
- Permiso USB, descriptores y eventos reales de enchufar/desenchufar: falta un
  accesorio OTG. La prueba con cero accesorios no valida esos caminos físicos.
- Monitor/inyección, firmware Nexmon, adaptadores externos, captura PCAP y
  control HID/USB gadget: no implementados ni anunciados como funcionales en
  esta versión de Operator.
- Broker privilegiado de producción con dominio SELinux propio, mutaciones
  transaccionales/undo e integración autorizada de Lamb: fase posterior.
- Incluir esta APK y `/product/bin/lambda` en el próximo ZIP de ROM y validarlo.

Implementación, contrato y comandos: [README de Operator](apps/Lambda/README.md).
