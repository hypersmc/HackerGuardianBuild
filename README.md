# HackerGuardian

HackerGuardian is a Minecraft anti-cheat plugin with optional AI-assisted detection.

## Building
1. Install Java (8 or higher) and [Maven](https://maven.apache.org/).
2. Run `mvn clean package` in the project root.
3. The final JAR is created in the `target` directory.

## Installing the Plugin
1. Copy the built JAR into your server's `plugins/` folder.
2. Start or reload the server to generate default configuration files.
3. Adjust `config.yml` (and `configbungee.yml` if using Bungee) to suit your environment.

## Configuration Highlights
- **SQLHost**, **SQLPort**, **SQLDatabaseName**, **SQLUsername**, **SQLPassword**: database connection settings.
- **EnableAI**: toggle experimental AI-based detection.
- **LearningMode**: allow the AI to learn new behaviours (alpha feature).
- **UseDBForAIData**: store AI training data in the configured database instead of locally.

See `src/main/resources/config.yml` for all options.

## AI Functionality
The plugin includes a neural network powered by the Neuroph library. When enabled, it can learn from gameplay events to assist cheat detection. Training data may be stored on disk or in a database.

## Optional Database Setup
If you wish to store data or leverage the website integration, configure a MySQL database and provide credentials in `config.yml`. The plugin will connect automatically on startup.

