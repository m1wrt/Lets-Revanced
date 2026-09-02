# AGENTS.md

## Proyecto
Este proyecto es una app Android que integra un backend local de detección de mirada usando visión por computadora. La funcionalidad principal no es un servidor remoto ni un servicio web: la detección ocurre en el dispositivo, en tiempo real, usando la cámara del teléfono.

La lógica original debe mantenerse intacta. El objetivo no es rediseñar la arquitectura ni reescribir el algoritmo, sino preservar el mismo flujo funcional que la versión original del proyecto.

## Objetivo principal
Reproducir la misma detección de mirada, el mismo flujo de calibración y la misma salida final de mirada que la versión original del backend.

## Arquitectura clave que debe preservarse
- Motor principal de detección: Model.java
- Clasificación por ojo y plantillas: EyeDetection.java
- Detección facial y landmarks: MediaPipeFaceDetector.java
- Persistencia de calibración y configuración: UserDataManager.java
- Coordinación entre UI y backend: Presenter.java
- Contratos/interfaz del sistema: ContractInterface.java
- Resultado de un frame: DetectionOutput.java
- Estructura de datos por ojo: GazeData.java

## Flujo funcional correcto
La pipeline de la app debe seguir exactamente este orden:
1. La cámara entrega frames en RGB.
2. Se detecta la cara y los landmarks relevantes.
3. Se extrae la región del ojo para cada lado.
4. Se compara esa ROI con las plantillas calibradas del usuario.
5. Se determina la dirección de la mirada para cada ojo.
6. Se combina la información de ambos ojos para devolver la salida final.
7. Se almacena/actualiza la información de calibración y estado.

## Reglas estrictas
- Mantener la lógica de clasificación de mirada sin cambios importantes.
- No reescribir el algoritmo de calibración.
- No cambiar los tipos de mirada ni el orden lógico de procesamiento.
- No simplificar ni romper la lógica de comparación con plantillas.
- Mantener la estructura del flujo original: frame -> detección -> comparación -> resultado final.
- Si se mueve el proyecto a otra app, ajustar solo paquetes e imports.
- Si hay fallos de compilación, arreglar imports y configuración, no reescribir la lógica de visión.
- Mantener UserDataManager como almacenamiento de calibración y configuración del usuario.
- Mantener Presenter como coordinador principal entre la UI y el motor de visión.

## Archivos clave a considerar
En la versión original, estos archivos representan el backend funcional:
- Model.java
- EyeDetection.java
- MediaPipeFaceDetector.java
- UserDataManager.java
- Presenter.java
- ContractInterface.java
- DetectionOutput.java
- GazeData.java

## Dependencias esperadas
La app debe tener soporte para:
- OpenCV Android
- CameraX
- MediaPipe Face Landmarker
- AndroidX lifecycle, appcompat y datastore/preferences si se usan en la lógica de persistencia

## Validación requerida antes de cerrar la integración
La IA o desarrollador debe comprobar que:
- compila sin errores de import o package
- abre la cámara correctamente
- detecta cara y ojos con MediaPipe
- carga y guarda plantillas de calibración
- compara las ROIs con los datos calibrados
- devuelve la mirada final en tiempo real
- mantiene el mismo comportamiento de la versión original

## Prompt reutilizable para la IA
Integrar esta aplicación Android manteniendo exactamente el mismo comportamiento funcional de la versión original. No reescribas la lógica ni cambies el algoritmo de detección de mirada. Debes conservar la arquitectura y el flujo actual del backend de visión.

Archivos clave:
- Model.java
- EyeDetection.java
- MediaPipeFaceDetector.java
- UserDataManager.java
- Presenter.java
- ContractInterface.java
- DetectionOutput.java
- GazeData.java

Objetivo:
Reproducir en la nueva app la misma detección de mirada, el mismo flujo de calibración y la misma salida final de mirada, sin romper la lógica original ni cambiar los tipos de mirada ni los estados de calibración.

Reglas obligatorias:
- Mantener la pipeline original: frame → detectar cara → extraer ROI del ojo → comparar con plantillas → devolver mirada final.
- No cambiar la lógica de clasificación ni los nombres de los tipos de mirada.
- Ajustar solo package names e imports si es necesario al mover el código.
- Añadir las dependencias necesarias para OpenCV, CameraX y MediaPipe si faltan.
- Mantener UserDataManager como almacenamiento de plantillas y estado de calibración.
- Verificar que la cámara esté conectada al modelo y que Presenter coordine la interacción con la UI.
- Si falla la compilación, corregir imports o configuración, pero nunca reescribir la lógica de visión.
- Confirmar que la calibración funciona en los mismos pasos que la versión original.
- Al terminar, validar compilación, carga de cámara, flujo de calibración y detección en tiempo real.

Importante:
Trata el proyecto como un motor de visión local, no como un backend remoto. La funcionalidad principal no es un servicio de servidor, sino un pipeline de análisis de video en Android. Debes preservar ese comportamiento exacto.
