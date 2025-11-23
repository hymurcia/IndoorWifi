# IndoorWifi - Navegación y Localización Wi-Fi en Interiores (UdeC)

## Descripción del Proyecto

IndoorWifi  es una aplicación Android diseñada para la localización en interiores dentro de un entorno específico (como un campus universitario) utilizando la técnica de huella digital Wi-Fi (Wi-Fi Fingerprinting). La aplicación permite a los usuarios mapear diferentes salones o ubicaciones registrando las características de las redes Wi-Fi visibles y luego utilizar estos datos para estimar la ubicación actual del usuario dentro de las áreas mapeadas.

Este proyecto fue desarrollado como parte de la asignatura de Machine Learning 802 por Hernan Yessid Murcia Salinas y Carlos Felipe Gomez Plazas.

## 📥 Descargar aplicación
[Descargar APK] (https://github.com/hymurcia/IndoorWifi/blob/master/IndoorWifi.apk?raw=1)



## Características Principales

* **Mapeo de Salones/Ubicaciones:**
    * Permite al usuario ingresar un nombre para un salón o ubicación específica.
    * Realiza un escaneo de las redes Wi-Fi disponibles en ese punto.
    * Guarda una "huella digital" (fingerprint) del salón, que consiste en una lista de los Puntos de Acceso (APs) visibles (identificados por su BSSID) y la intensidad de su señal (RSSI).
    * **Soporte para Múltiples Muestras:** Permite tomar varias muestras de huellas Wi-Fi para un mismo salón, mejorando la robustez de los datos contra fluctuaciones de la señal. Los usuarios pueden añadir nuevas muestras a un salón existente o reemplazar todas las muestras anteriores.
    * **Filtrado de APs Débiles:** Ignora las señales Wi-Fi con RSSI por debajo de un umbral configurable (`MIN_RSSI_THRESHOLD`) durante el mapeo y la localización para mejorar la calidad de los datos.

* **Localización en Interiores:**
    * Realiza un escaneo Wi-Fi de la ubicación actual del usuario.
    * Compara la huella del escaneo actual con las huellas guardadas de todos los salones mapeados.
    * Utiliza un algoritmo **k-Vecinos Más Cercanos (k-NN)** para determinar el salón más probable.
    * Calcula una **distancia euclidiana** entre las huellas, considerando un valor por defecto para APs no presentes en ambos conjuntos.
    * Proporciona un **feedback de confianza** sobre la predicción (Alta, Media, Baja, Muy Baja) y un mensaje de "Ubicación Desconocida" si la coincidencia no es suficientemente buena (basado en `MAX_ACCEPTABLE_DISTANCE_FOR_LOCALIZATION`).

* **Gestión de Datos:**
    * **Persistencia de Datos:** Todas las información de los salones y sus muestras de huellas Wi-Fi se guardan localmente en el dispositivo en un archivo JSON (`salones_data.json`), permitiendo que los datos persistan entre sesiones de la aplicación.
    * **Administración de Salones:**
        * Ver una lista de todos los salones mapeados, incluyendo el número de muestras recolectadas para cada uno.
        * Opción para ver los detalles de las muestras individuales de un salón (listado de APs y RSSI).
        * Opción para borrar muestras individuales de un salón.
        * Opción para borrar todas las muestras de un salón (dejando el salón en la lista pero sin huellas).
        * Opción para borrar un salón completo (con todas sus muestras) de la base de datos.
    * **Exportar Datos:** Permite al usuario exportar todos los datos de los salones mapeados a un archivo JSON en una ubicación de su elección.
    * **Importar Datos:** Permite al usuario importar datos de salones desde un archivo JSON previamente exportado, reemplazando los datos actuales.

* **Interfaz de Usuario:**
    * Botones dedicados para iniciar el mapeo, actualizar la ubicación, administrar salones, exportar/importar datos y ver créditos.
    * Diálogos para la entrada de nombres de salón y confirmaciones de acciones.
    * `TextView` para mostrar el salón más cercano predicho, el nivel de confianza y un `ScrollView` para información de depuración detallada (como los resultados del escaneo Wi-Fi y las distancias calculadas).

* **Permisos:**
    * Maneja adecuadamente los permisos de `ACCESS_FINE_LOCATION` en tiempo de ejecución, necesarios para el escaneo de Wi-Fi.
    * Solicita al usuario activar el Wi-Fi si está desactivado.

## Requisitos Técnicos

* **Lenguaje:** Kotlin
* **SDK Mínimo:** API 24 (Android 7.0 Nougat)
* **Configuración de Compilación:** Kotlin DSL (`build.gradle.kts`)
* **Bibliotecas Principales:**
    * AndroidX AppCompat, Core KTX
    * Google Material Components (para `FloatingActionButton`, etc.)
    * Gson (para serialización/deserialización JSON)

## Cómo Empezar (Para Desarrolladores)

1.  **Clonar el Repositorio:**
    ```bash
    git clone [https://github.com/hymurcia/IndoorWifi.git]
    ```
2.  **Abrir en Android Studio:**
    * Abre Android Studio (versión recomendada: 21.0.6).
    * Selecciona "Open an existing Android Studio project".
    * Navega hasta la carpeta clonada y selecciónala.
3.  **Sincronizar con Gradle:** Android Studio debería sincronizar el proyecto automáticamente. Si no, ve a `File > Sync Project with Gradle Files`.
4.  **Ejecutar la Aplicación:**
    * Selecciona un emulador o conecta un dispositivo físico Android (con opciones de desarrollador y depuración USB habilitadas).
    * Asegúrate de que los servicios de ubicación y Wi-Fi estén activados en el dispositivo/emulador.
    * Ejecuta la aplicación.

## Flujo de Uso Básico

1.  **Mapear Salones:**
    * Ve a la ubicación física del salón que deseas mapear.
    * Presiona el botón "+" (Añadir Salón).
    * Ingresa un nombre descriptivo para el salón y presiona "Guardar y Escanear" (o "Continuar").
    * Si el salón ya existe, la app te preguntará si deseas añadir una nueva muestra o reemplazar las existentes.
    * La app realizará un escaneo Wi-Fi.
    * Después de que se guarde la muestra, la app te preguntará si deseas tomar otra muestra para el mismo salón, facilitando la recolección de múltiples huellas.
2.  **Localizarse:**
    * Una vez que hayas mapeado algunos salones, presiona el botón "Actualizar Ubicación".
    * La app realizará un escaneo Wi-Fi y mostrará el salón más cercano que encuentre, junto con un indicador de confianza.
3.  **Administrar Salones:**
    * Presiona "Administrar Salones" para ver la lista de salones mapeados y el número de muestras de cada uno.
    * Selecciona un salón para ver opciones como "Ver detalles de muestras", "Borrar todas las muestras", o "Borrar salón completo".
4.  **Exportar/Importar:**
    * Usa los botones respectivos para guardar tus datos de mapeo en un archivo o cargar datos desde un archivo.

## Posibles Mejoras Futuras

* Implementar una interfaz más avanzada para "Administrar Salones" usando `RecyclerView`.
* Guardado/carga de datos en un hilo secundario para bases de datos muy grandes.
* Visualización de la ubicación en un plano cargado por el usuario.
* Refinamiento continuo del algoritmo de localización y los umbrales.
* Modo de localización continua.



## Créditos

* **Desarrolladores:** Hernan Yessid Murcia Salinas, Carlos Felipe Gomez Plazas
* **Materia:** Machine Learning 802
* **Universidad:** Universidad de Cundinamarca

---
