> Nota de publicación: las rutas locales del equipo de desarrollo se han convertido a rutas relativas del repositorio.

# Samsung S10: investigación de cracking CPU + GPU

Fecha: 31 de octubre de 2026. Dispositivo medido: Samsung Galaxy S10
`beyond1lte`, Exynos 9820, Mali-G76, Android 15 / LambdaOS 0.1.0-alpha.2,
kernel `4.14.356-lambdaos+`.

## Conclusión

**Sí es viable aprovechar simultáneamente CPU y GPU en este S10.** Se ha
demostrado con un cálculo PBKDF2-HMAC-SHA1 de 4.800 candidatos sintéticos,
verificando los 32 bytes de cada PMK contra OpenSSL: cero discrepancias.

Esto **no equivale aún a un motor híbrido de cracking integrado en Operator**.
El prototipo GPU usa contraseña de 15 bytes y un SSID sintético fijo. Tampoco
demuestra el máximo absoluto del hardware ni rendimiento sostenido durante horas.

Además, una optimización CPU ya probada dentro de una copia experimental de
Aircrack-ng reduce aproximadamente un tercio el tiempo del lote WPA/PMKID.
La APK y el motor CPU estable no se han sustituido en esta investigación.

## 1. Comparativa completa con Aircrack-ng

Mismo teléfono, ocho hilos, mismos fixtures, tres repeticiones por motor.
Orden: original, SHA, SHA, original, original, SHA; seis segundos de pausa
entre grupos. Cada grupo verifica primero la contraseña sintética conocida
en WPA2 y PMKID. Todos completaron esas comprobaciones.

| Medida, mediana de 3 | Aircrack original | Variante CPU SHA | Cambio |
|---|---:|---:|---:|
| Benchmark del motor, claves/s | 3.574,313 | 5.429,802 | +51,9 % |
| Lote completo WPA2 de 4.800, s | 1,759929 | 1,173557 | −33,3 % |
| Lote completo PMKID de 4.800, s | 1,724522 | 1,150832 | −33,3 % |

El benchmark del motor es la última tasa de `aircrack-ng -S -Z 10`.
Su etiqueta `k/s` significa **keys/second**, no miles de claves por segundo.
Los tiempos de lote de esta tabla incluyen el lanzamiento y retorno por ADB;
no tienen exactamente la misma instrumentación que el informe previo de la APK.
Las mejoras se calculan contra la referencia repetida en esta misma cohorte.

El lote utiliza el diccionario Probable WPA Top4800 v2 ya validado, con la
contraseña sintética deliberadamente ausente: se prueban los 4.800 candidatos.
No se han utilizado capturas de terceros.

### Qué cambia en la CPU

La copia experimental sustituye únicamente la derivación de PMK cuando
`HWCAP_SHA1` confirma las extensiones SHA de ARM. Reutiliza estados internos
HMAC y llama a la implementación ARM de OpenSSL; conserva la comprobación
WPA/PMKID de Aircrack y el camino anterior como fallback.

El helper se contrastó con 100 vectores de diferentes longitudes de contraseña
y SSID, comparando los 32 bytes de salida con `PKCS5_PBKDF2_HMAC_SHA1`.
Su benchmark aislado no se mezcla con las cifras de Aircrack de la tabla.

También se recuperaron correctamente **12/12 PMKID sintéticos** desde la
variante real de Aircrack, con claves de 8, 15 y 63 bytes; SSID de 1, 15 y
32 bytes, además de un SSID UTF-8. Cada diccionario incluía dos candidatos
incorrectos y el correcto. Estos PMKID se generan independientemente en el
portátil con `hashlib.pbkdf2_hmac` y HMAC, no con el helper que se está evaluando.

## 2. CPU + GPU: cálculo simultáneo validado

Se escribió un pequeño kernel OpenCL independiente, con inicialización HMAC y
iteraciones PBKDF2 repartidas en llamadas cortas. Desenrollar las 80 rondas de
SHA-1 cambió el rendimiento del primer prototipo GPU: de unos 667 a unos
4.426 PMK/s en pasadas individuales. La GPU sola osciló entre 3.989 y 4.426
PMK/s en las dos pasadas optimizadas; no es una caracterización sostenida.

Para la comparación repetida, cada lote contiene los mismos 4.800 candidatos:
CPU sola procesa todos; el híbrido asigna 2.400 a CPU y 2.400 a GPU, sin
duplicar trabajo. Se verifican **todos** los resultados después de cada pasada.

| Configuración | Pasadas | PMK/s, mediana | Cálculo de 4.800, mediana |
|---|---:|---:|---:|
| CPU SHA, 8 hilos | 3 | 4.963,535 | 0,967053 s |
| CPU SHA + Mali, reparto 50/50 | 3 | **9.449,321** | **0,507973 s** |

Mejora del híbrido respecto a CPU sola en este mismo ensayo: **+90,4 %** de
rendimiento, aproximadamente **−47,5 %** de tiempo de cálculo.
Rango híbrido: 9.219,962–9.668,989 PMK/s. Todas las pasadas: 0 discrepancias.
Los repartos con solo 256, 512 o 1.024 candidatos en GPU fueron inferiores al
50/50 en esta exploración; aún no hay un planificador adaptativo.

### Lo que esas cifras NO incluyen

- Inicializar OpenCL y compilar el pequeño programa costó aproximadamente
  **1,0 segundo por proceso**. Se conserva por separado como `build_setup_s`.
- La validación independiente de los 4.800 resultados se realiza **después**
  del intervalo cronometrado. Explica gran parte de los ~10 segundos de duración
  total del proceso de prueba, y también influye en la temperatura posterior.
- No incluyen lectura de una captura, selección de handshake, verificación
  final de MIC/PMKID, GUI, cancelación de una tarea real ni persistencia.
- El prototipo GPU está especializado al fixture, no acepta SSID, hash,
  contraseña o captura arbitrarios. Debe generalizarse y validarse antes de
  utilizarse para decidir que una contraseña es incorrecta.

Por ello, **9.449 PMK/s no se publicita como 9.449 contraseñas/s del flujo
completo de Operator**. Tampoco se compara directamente con el benchmark de
Aircrack como si fueran idénticas mediciones.

Para 4.800 claves, arrancar desde cero el híbrido (~1,0 + 0,51 s de setup y
cálculo) puede costar más que ejecutar la CPU optimizada. Para diccionarios
largos o un contexto OpenCL reutilizable, el coste inicial podría amortizarse;
esa integración aún no está medida.

## 3. Hashcat: qué funciona y qué no

Hashcat admite varios dispositivos y tipos de dispositivo en el mismo sistema,
pero necesita runtimes compatibles. En este S10, la enumeración completa de
OpenCL muestra **solo Mali-G76**, no un dispositivo CPU OpenCL. No basta con
seleccionar CPU y GPU mediante una opción de Hashcat.
[Documentación oficial de Hashcat](https://hashcat.net/hashcat/).

La guía Android de PoCL deshabilita los dispositivos CPU y el cargador ICD en
su configuración documentada. Instalar PoCL sin más no resolvería la unión de
CPU y GPU nativas; haría falta un port específico o una integración distinta.
La prueba realizada evita esa dependencia usando CPU nativa + OpenCL GPU.
[PoCL para Android](https://portablecl.org/docs/html/android.html).

| Prueba aislada | Resultado observado |
|---|---|
| Hashcat 8, enumeración | Detecta Mali-G76, OpenCL 3.0; no runtime CPU |
| Hashcat 8, WPA, no-inline | Falla la autocomprobación criptográfica, salida 245 |
| Hashcat 8, vector width 2 y caché nueva | Falla la autocomprobación después de compilar |
| Hashcat 8, inline real | Compilación cortada por plazo de 60 s; ~818 MiB RSS |
| Hashcat 8, MD5 con respuesta conocida | Su self-test pasa, pero no recupera la clave sintética presente: se rechaza el resultado |
| Hashcat 6.2.6 experimental, MD5 | Compilación interrumpida por umbral RSS de 1 GiB |

La build 6.2.6 incluye un ajuste de afinidad para Android y un presupuesto
reportado de 512 MiB para limitar las pruebas de asignación. No se modifican
sus algoritmos. Tras alcanzar el umbral se detuvo la serie: **no se ejecutaron
las pruebas 16800/22000 previstas para esa serie**.

No se ha localizado todavía la causa precisa del fallo de corrección de
Hashcat: puede estar en la combinación de build, opciones, código y compilador
del driver. No se atribuye definitivamente al hardware. El kernel independiente
correcto demuestra que la GPU sí puede realizar este cálculo.

Se conservaron intentos preliminares no válidos: flags `-T` rechazados por v8
y una pasada denominada `inline` que aún tenía definida la variable no-inline.
No se contabilizan como resultados de rendimiento ni como prueba de inline.
La pasada corregida está en `research-1793453187/inline.json`.

**No se usaron `--force`, `--self-test-disable` ni se desactivó protección térmica.**

## 4. Memoria, temperatura y límites

En la matriz híbrida repetida, el RSS máximo por proceso rondó 73–74,4 MiB.
Esto incluye el proceso host y el compilador: **no es memoria GPU total ni PSS**.
Las muestras previas/posteriores registraron batería entre 33,5 y 34,6 °C;
BIG hasta 60 °C y G3D hasta 48 °C. No son máximos continuos del instante de
cálculo: las muestras posteriores incluyen la verificación CPU de referencia.

No se mide energía por intento ni se infiere una mejora de autonomía. El
teléfono está conectado por USB. Faltan ensayos prolongados de temperatura,
frecuencia y rendimiento sostenido, además de energía con instrumentación adecuada.

El guard conserva plazos de hasta 60 s, umbral de 1 GiB RSS, mínimo de 768 MiB
de memoria disponible, batería a 42 °C y SoC a 85 °C. Un umbral supervisado no
es una reserva física rígida: la compilación de Hashcat 6.2.6 llegó a ~1.054 GiB
antes de finalizar la parada. Los motores se ejecutan como UID 2000, sin
grupos suplementarios. Se usa ADB root solo para aprovisionamiento/supervisión
y los scripts restauran ADB a shell al finalizar.

No se modifica el driver, el kernel, las frecuencias ni SELinux; no se mata
ningún servicio ajeno ni se descarga Lamb por la fuerza. No se flashea ni
reinicia el teléfono. Las pruebas de radio no forman parte de esta investigación.

Comprobación final: Core con 0 tareas activas, ningún proceso Aircrack/Hashcat/
probe/guard pendiente, ADB como UID 2000, SELinux Enforcing y
`sys.boot_completed=1`. El SHA-256 del Aircrack estable del teléfono sigue
siendo el original. El portátil conserva aproximadamente 46 GiB libres y el
S10 aproximadamente 83 GiB libres.

## 5. Artefactos y reproducción

Dentro del repositorio:

- `alpha/scripts/build_cracking_research.py`: reconstruye las variantes
  experimentales sin sustituir Aircrack estable. Probado como kali y mediante
  sudo/root; root delega la compilación al usuario kali.
- `alpha/testing/compare_cpu_sha_s10.py`: matriz A/B de Aircrack.
- `alpha/testing/compare_hybrid_pmk_s10.py`: matriz PBKDF2 CPU/GPU validada.
- `alpha/testing/verify_sha_pmkid_s10.py`: pruebas de longitud y SSID adicionales.
- `alpha/testing/investigate_hashcat_s10.py`: casos limitados de compatibilidad.
- `alpha/apps/Lambda/native/pmk-sha1.h`: helper CPU experimental.
- `alpha/apps/Lambda/native/pmk-gpu-probe.{c,cl}`: prototipo sintético híbrido.
- `alpha/out/cracking-speed/research-build-manifest.json`: hashes de compilación.

Datos brutos, bajo `alpha/out/cracking-speed/`:

- `cpu-sha-comparison-1793453689/results.json`: 30 comprobaciones, seis grupos.
- `pmk-cpu-initial.json`: 100 vectores y exploración CPU aislada.
- `pmk-gpu-initial.json`: primer kernel GPU, sin desenrollado.
- `pmk-hybrid-1793453966/`: exploración inicial de repartos.
- `pmk-hybrid-1793454108/`: comparativa CPU/híbrido repetida y GPU adicional.
- `research-1793453062/`, `research-1793453187/`: Hashcat 8, incluido ensayo corregido.
- `research626-1793453446/`: Hashcat 6.2.6 y parada por memoria.
- `sha-boundaries-1793454398/results.json`: 12/12 PMKID de longitudes límite
  y UTF-8 correctos. El intento anterior `sha-boundaries-1793454281/` omitía
  el selector ESSID requerido por la CLI; se corrigió el harness y se repitió.
  No fue una discrepancia criptográfica ni se mezcla con la serie válida.

Hashes de los binarios principales medidos:

| Binario | SHA-256 |
|---|---|
| Aircrack original | `7c92c3f66fef9f746495718f3817e93e207c1cc208dd5838ae922a6ce7f4d944` |
| Aircrack CPU SHA | `c1d6114351352e683e51712be8d5769e34493ecda95aef7af06a29b2db73295c` |
| Probe híbrido | `a00e29ca31bfefbdc12ff95f6ea43da1c7320be6f62933c14a1717814b61ab87` |
| Kernel OpenCL desenrollado | `2922272367c7ce4e7168b7a0016d855ff97a157d658312c36d3a2c8c29892727` |
| Hashcat 6.2.6 experimental | `d1908b462c6ef9fd63eb6a684184eb0385bdb12444a753f62f5aa8b331ecef69` |

Las fuentes y licencias originales se conservan en `vendor/` y `notices/`.
La variante de Aircrack es modificada y debe distribuirse con sus fuentes y
obligaciones de licencia, no como un binario upstream sin modificaciones.
El código ARM de OpenSSL usado como referencia está documentado en
[sha1-armv8.pl](https://github.com/openssl/openssl/blob/master/crypto/sha/asm/sha1-armv8.pl).

## 6. Siguiente integración recomendada

1. Promover la variante CPU solo después de repetir las pruebas de control,
   cancelación y ciclo de vida a través de Core/APK, conservando rollback.
2. Para el híbrido, aceptar longitudes WPA y SSID generales; contrastar vectores
   límite y un corpus de handshakes/PMKID sintéticos de respuesta conocida.
3. Añadir comprobación MIC/PMKID, avance real, cancelación, reparto adaptativo y
   recuperación/reintento CPU de trabajo GPU fallido. No declarar agotado un
   diccionario tras un fallo de GPU ni saltarse candidatos.
4. Reutilizar o almacenar la compilación OpenCL con clave de caché que incluya
   fuente, opciones y driver. Medir por separado arranque frío y caliente.
5. Medir lotes largos, consumo y throttling. Escoger CPU para lotes pequeños
   si el arranque GPU no compensa; reservar el híbrido para donde gane realmente.

La mejora CPU está validada en una variante aislada; la mejora híbrida es una
prueba de viabilidad correcta y prometedora. **Ninguna se presenta todavía
como desplegada en la APK o empaquetada en una ROM nueva.**
