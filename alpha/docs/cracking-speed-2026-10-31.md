# Lambda Operator — WPA/PMKID speed test

Investigación posterior, separada de la APK estable: [CPU SHA y CPU + GPU en
el S10](cracking-cpu-gpu-research-2026-10-31.md). Las nuevas cifras de prototipo
no sustituyen automáticamente los resultados de esta versión instalada.

## Estado

Implementado en la APK nativa y probado en el Samsung Galaxy S10 `beyond1lte`.
Ruta: **Ajustes → Developer mode → Cracking speed test → WPA / PMKID**.

No se ha flasheado ni recompilado la ROM. Los motores se aprovisionan por ADB
como herramientas de desarrollo; el worker requiere volver a aprovisionarse tras
un reinicio. La APK conserva la firma existente de Lambda.

El benchmark no acepta hashes, capturas, diccionarios ni comandos arbitrarios.
Usa exclusivamente fixtures sintéticos reproducibles: no escanea redes, no
captura tráfico ni envía deauth. No está conectado todavía al flujo automático
de cracking posterior a una captura real.

## Resultados CPU

Aircrack-ng 1.7 ARM64, OpenSSL 3.5.9, NDK 27.1.12297006 / API 29.
Tres repeticiones por configuración; las nueve terminaron correctamente.
Orden rotatorio: 1, 4, 8, 8, 1, 4, 4, 8, 1; seis segundos entre pasadas.

| Hilos | Motor WPA, mediana (intentos/s) | WPA2: lote de 4.800 (s) | PMKID: lote de 4.800 (s) | RSS máximo del motor, mediana (MiB) |
|---:|---:|---:|---:|---:|
| 1 | 859,64 | 5,853 | 5,773 | 9,52 |
| 4 | 2.470,59 | 2,280 | 2,287 | 15,58 |
| 8 | **3.505,67** | **1,872** | **1,772** | 23,60 |

Rangos observados de velocidad del motor: 851–897, 2.436–2.475 y
3.448–3.532 intentos/s, respectivamente. Para la comprobación rápida, 8 hilos
es la opción más rápida de las medidas. La prueba integrada permite comparar
las tres configuraciones; la selección inicial se mantiene en 4 hilos.

### Qué significa cada cifra

- **Motor WPA:** última tasa informada por `aircrack-ng -S -Z 10`; es una tasa
  acumulada de su benchmark de diez segundos, no una media de todo el flujo UI.
  El `k/s` impreso por Aircrack significa **keys/second**, no miles de claves/s.
- **Lote de 4.800:** tiempo real desde iniciar el proceso protegido hasta que
  termina, incluyendo puesta en marcha y comprobación de salida. La contraseña
  sintética está fuera del diccionario, por lo que se agota el lote completo.
- Cada pasada comienza recuperando la contraseña conocida tanto de un
  handshake WPA2 como de un PMKID. Un resultado de velocidad no se acepta si
  falla esa comprobación.
- **RSS:** máximo del proceso nativo recogido mediante `wait4`, no PSS, memoria
  total de Operator, memoria del sistema ni asignaciones totales de la GPU.

La batería llegó como máximo a **34,5 °C** en la matriz; los sensores del SoC
llegaron a **81 °C**. Son sensores distintos y no deben compararse entre sí.
El teléfono estaba conectado por USB, con batería al 100 % y estado `Full`:
esto **no permite medir consumo energético ni autonomía** de forma fiable.
Son pruebas cortas, no una certificación de rendimiento sostenido durante horas.

## GPU: detectada, pero no validada

La Mali-G76 aparece correctamente mediante OpenCL y Hashcat 8.0.0.
Sin embargo, **no se ha obtenido una ejecución criptográficamente válida** con
la configuración probada. Por ello no se publican intentos/s de GPU.

Secuencia de diagnóstico conservada:

1. Kernel normal: la compilación fría superó el límite de 60 segundos. El guard
   terminó el proceso; RSS máximo aproximado de 819 MiB.
2. `HASHCAT_FORCE_NO_INLINE=1`: compilación mucho más contenida, pero los
   presupuestos de 128/256 MiB no bastaron para la contabilidad de memoria de
   Hashcat. En su código v8, la memoria unificada incluye una reserva de host
   de 512 MiB; esa reserva no equivale a memoria físicamente ocupada.
3. Presupuesto unificado de 768 MiB, workload 1, vector width 1, no-inline:
   llega al self-test, pero informa **OpenCL kernel self-test failed** y termina
   con código 245. RSS máximo aproximado de 251 MiB en esa prueba.

No se usaron `--force`, `--self-test-disable` ni modificaciones del driver.
El fallo corresponde a esta combinación de build, opciones y driver; no prueba
que toda aceleración GPU en el S10 sea imposible. La opción aparece como
experimental con un aviso explícito. Resolverla requiere una prueba separada
de compatibilidad/corrección antes de compararla en rendimiento.

Hashcat no encuentra su interfaz propia de monitorización térmica Android.
Eso no desactiva las protecciones del sistema ni nuestro guard externo.

## Ejecución y límites

- Cola exclusiva de Lambda Core: rechaza nuevas tareas mientras corre el test.
- La tarea continúa al salir de su pantalla; resultados y estados quedan en
  SQLite y pueden consultarse/exportarse desde Sesiones.
- El supervisor de mantenimiento es root. Los motores de cálculo se ejecutan
  como **UID 2000**, sin grupos suplementarios ni acceso a un shell arbitrario
  a través de la API de Operator.
- Límites independientes: plazo por proceso, memoria RSS del motor ≤1 GiB,
  parada si `MemAvailable` cae por debajo de 768 MiB, batería ≥42 °C o sensores
  BIG/MID/LITTLE/G3D ≥85 °C. Se conserva el límite térmico nativo de Android.
- Cancelación, terminación del worker y muerte del proceso padre provocan la
  limpieza de sus hijos mediante el supervisor nativo.
- No se cambian gobernadores, SELinux, frecuencia, voltaje, ADB, servicios
  críticos ni aplicaciones ajenas. ADB queda como UID 2000 y SELinux Enforcing.
- Lamb no está integrado todavía con este coordinador. No se presenta como
  implementada una descarga automática de su modelo; tampoco se matan procesos
  ajenos para simularla. El futuro runtime debe ofrecer una liberación explícita.

Se corrigió durante las pruebas un límite de CPU que sumaba el tiempo de todos
los hilos y cortaba la pasada de ocho hilos. La matriz publicada se repitió
después de la corrección. Los intentos fallidos anteriores se conservan fuera
de la cohorte final, no se mezclan con sus medianas.

## Datos y reproducibilidad

### Verificación de la APK final

- Instalación final verificada por SHA-256 en el archivo `base.apk` del S10:
  `960fcc163774ba7deda7137c2557f5b5a187d67034a9e2dd763e2974459641f4`.
- **13 comprobaciones de protocolo y control superadas**: argumentos cerrados,
  permisos del worker, idempotencia, exclusividad, cancelación y ausencia de
  procesos de cálculo huérfanos. Evidencia: `controls-qa.json`.
- Cancelación real pulsando **Detener prueba** en pantalla confirmada como
  `cancelled`; evidencia `ui-stop-verified.json` y captura `ui-live.png`.
- Una pasada CPU siguió ejecutándose al enviar la actividad a Inicio y volver.
  Terminó correctamente: `final-lifecycle.json` y `final-aircrack.json`.
  Esta pasada de aceptación no se mezcla con las nueve de la tabla.
- Prueba GPU final: error explícito `opencl_selftest_failed`, sin aceptar una
  tasa de cracking válida; evidencia `final-hashcat.json`.
- Al terminar, Core sin tareas activas, sin motores de cálculo pendientes,
  ADB UID 2000 y SELinux Enforcing: `final-device-checks.json`.

El registro de fallos conserva errores de asignación del linker/Scudo durante
las pruebas preliminares con límites de espacio virtual; se retiraron esos
límites incompatibles con el runtime Android. No se han borrado esos registros
ni se interpretan como fallos de la APK final. Se mantiene el límite de RSS y
la supervisión de memoria disponible del sistema.

Datos brutos de la matriz final:
`alpha/out/cracking-speed/matrix/bench-matrix-1793451686-*.json`.
Cada fila retiene tiempos, salida nativa, sensores, RSS y estado de validación.
Diagnósticos GPU: `gpu-xdg.json`, `gpu-noinline.json`, `gpu256.json`,
`gpu768.json` dentro de `alpha/out/cracking-speed/`.

Scripts:

- `alpha/scripts/build_cracking_bench.py`: Aircrack-ng/OpenSSL, fuentes fijadas por SHA-256.
- `alpha/scripts/build_hashcat_bench.py`: Hashcat, guard, probe y licencias.
- `alpha/scripts/prepare_cracking_fixtures.py`: fixtures y diccionario verificado.
- `alpha/scripts/install_cracking_bench_dev.sh`: instalación explícita; devuelve ADB a shell.
- `alpha/testing/cracking_benchmark_matrix.py`: matriz real mediante Core API.
- `alpha/testing/verify_cracking_controls.py`: rechazo de argumentos, exclusividad,
  idempotencia, cancelación y limpieza de procesos.

SHA-256 de Aircrack-ng medido:
`7c92c3f66fef9f746495718f3817e93e207c1cc208dd5838ae922a6ce7f4d944`.
Los nuevos recibos incluyen hashes del motor, del guard y del manifiesto de fixtures.
Los avisos/licencias se conservan en `alpha/out/cracking-speed/notices/`.

El diccionario sigue siendo el **Probable WPA Top4800 v2 original**, con 4.800
entradas únicas de 8–15 caracteres. Véase
[atribución y componentes](cracking-benchmark-third-party.md).

## Referencias técnicas

La revisión de documentación oficial guio la separación entre benchmark y lote
real, el uso de la API OpenCL y los límites preventivos de las pruebas móviles:
[Aircrack-ng](https://www.aircrack-ng.org/doku.php?id=aircrack-ng),
[Hashcat Android](https://github.com/hashcat/hashcat/blob/v8.0.0/BUILD_Android.md),
[código de memoria de Hashcat 8.0](https://github.com/hashcat/hashcat/blob/v8.0.0/src/backend.c).

## Siguiente integración

Usar el motor CPU validado para la comprobación automática de 4.800 claves tras
una captura completa; después, añadir el flujo dedicado con diccionarios largos,
progreso real y cancelación. La captura debe conservarse incluso si falla o se
cancela el cracking. La GPU no debe decidir que una contraseña es incorrecta
hasta superar pruebas de corrección independientes.
