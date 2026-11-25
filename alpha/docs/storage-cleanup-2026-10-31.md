# Limpieza autorizada — 31 de octubre de 2026

Petición: retirar los modelos de benchmark distintos de Gemma 4, las ROM anteriores
y la caché de actualizaciones de Codex. No se pidió borrar fuentes, resultados ni backups.

## Resultado observado

En el sistema de archivos raíz del portátil, el espacio disponible pasó de unos
**1,2 GiB a 46 GiB** según `df -h /`. Aproximadamente **45 GiB recuperados**.
El espacio libre restante no equivale al tamaño total del disco menos los archivos:
ext4 también mantiene bloques reservados y metadatos.

## Retirado

- Catorce archivos GGUF de los benchmarks: Granite 4.2 3B/8B; Ministral 3 3B/8B;
  LFM 2.5 350M QAD, 1.2B Instruct QAD, 2.6B, 2.6B QAD y 8B-A1B;
  Qwen 3.5 0.8B/2B/4B; SmolLM3 3B; MiniCPM5 2B.
- Ocho ZIP antiguos de LambdaOS: los dos Alpha 1 y los de `release-alpha2`,
  `release-alpha2-ui-final`, `ui11`, `ui12`, `ui16` y `ui17`.
- Paquetes y cuatro árboles temporales del actualizador de Codex:
  `26.1021.22027`, `26.1027.30824`, `26.1023.50603` y `26.1023.51421`.
  Se comprobó que no hubiera archivos abiertos en esos árboles antes de borrarlos.

## Conservado

- Gemma 4 E2B GGUF, E2B LiteRT-LM y E4B GGUF; también sus enlaces duros de runtime.
- La última ROM disponible:
  `alpha/out/release-alpha2-kernel-fcm-v4/LambdaOS-0.1.0-alpha.2-beyond1lte-clean-install.zip`.
- Backups, kernels, claves de firma, recibos, informes, fuentes y resultados de pruebas.
- Codex instalado, el servicio actualizador y el historial de conversaciones.

No se borraron modelos del S10. La limpieza se realizó en el portátil cuyo disco
estaba lleno. El borrado es permanente: los modelos pueden descargarse de nuevo;
los ZIP antiguos requieren otra copia o recompilación.
