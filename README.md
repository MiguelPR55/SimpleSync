# SimpleSync

SimpleSync es un mod de Minecraft desarrollado para la plataforma **Fabric** (Java 21) que sincroniza automáticamente tus mundos de un solo jugador (*singleplayer*) con **Google Drive**.

## 🚀 Características

- **Sincronización en segundo plano:** Las operaciones de subida y bajada se ejecutan asíncronamente en un hilo secundario para evitar congelar el juego.
- **Detección y resolución de conflictos:** Si un mundo ha sido modificado tanto en local como en la nube, se muestra una interfaz gráfica para que decidas qué versión mantener.
- **Indicador visual en el HUD:** Muestra el estado del progreso en tiempo real (comprimiendo, subiendo, descargando, etc.) y porcentaje completado.
- **Robustez y seguridad:**
  - Recuperación automática ante cierres abruptos (limpieza y restauración de respaldos).
  - Prevención de ataques de *path traversal* (*zip-slip*).
  - Reintentos con *exponential backoff* ignorando fallos del cliente permanentes (4xx).

## 🛠️ Requisitos e Instalación

1. Asegúrate de tener **Minecraft** con **Fabric Loader** instalado.
2. Coloca el archivo `.jar` del mod en tu carpeta `mods/`.
3. Crea un proyecto en la consola de Google Cloud, habilita la API de Google Drive, crea credenciales de OAuth2 para una aplicación de escritorio, y descarga el archivo JSON de credenciales.
4. Coloca el archivo `client_secret.json` en la ruta `config/simplesync/client_secret.json`.
5. Al iniciar el juego, el mod te pedirá autorización a través de tu navegador la primera vez.

## 📄 Licencia

Este proyecto está bajo la licencia **MIT**. Consulta el archivo `LICENSE` para obtener más información.
