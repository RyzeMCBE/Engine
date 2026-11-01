# Publicación de Engine Server en Maven Central

> Estado: preparación técnica en [Engine PR #53](https://github.com/RyzeMCBE/Engine/pull/53).
> NO publicado, NO ejecutar la subida antes de cumplir todas las puertas
> de validación. Un upload a Sonatype **no significa** que sea público.

La biblioteca destinada a desarrolladores es:

```kotlin
repositories { mavenCentral() }
dependencies {
    implementation("io.github.ryzemcbe:engine-server:0.14.3-beta.1")
}
```

La publicación no es el servidor `shadowJar` ejecutable. La propiedad
`-PcentralRelease=true` afecta al POM de biblioteca y sus coordenadas;
las compilaciones normales y el JAR completo de Engine permanecen separados.

## Orden obligatorio: dependencias antes que Engine

1. Confirmar HTTP **200** para POM **y** JAR en
   `https://repo.maven.apache.org/maven2/` de las cuatro bibliotecas
   originales publicadas desde Protocol:
   `cloudburst-math-api:2.0.0-ryze.1`,
   `cloudburst-math-immutable:2.0.0-ryze.1`,
   `cloudburst-raknet:1.0.0.CR3-ryze.1` y
   `nukkit-natives-java:1.0.3-ryze.1`, todas bajo
   `io.github.ryzemcbe`. Si el deployment todavía figura como VALIDATED,
   el propietario debe pulsar Publish en Sonatype y comprobar su propagación.
2. Protocol [PR #7](https://github.com/RyzeMCBE/Protocol/pull/7) ya
   se fusionó a main. El workflow **manual**
   [publish-protocol-central.yml](https://github.com/RyzeMCBE/Protocol/blob/main/.github/workflows/publish-protocol-central.yml)
   genera, firma y sube `bedrock-codec:1.26.60-R1` y
   `bedrock-connection:1.26.60-R1` en un deployment con aprobación manual.
   Solo continuar tras verificar HTTP 200 en sus POM/JAR.
3. Confirmar HTTP 200 de StateUpdater:
   `common:0.1.1`, `block-updater:1.26.60-R1`,
   `item-updater:1.26.50-R1`, y de Engine API
   `engine-api:0.30.0`, todos en `io.github.ryzemcbe`.
4. Publicar **primero** los cinco módulos complementarios preparados desde
   fuentes originales, ya que los snapshots externos no están en Maven Central:
   - `ryze-transport-common:2.0.0.CR6-ryze.1`
   - `ryze-netty-transport-nethernet:2.0.0.CR6-ryze.1`
   - `ryze-okaeri-configs-core:5.0.13-ryze.1`
   - `ryze-okaeri-configs-yaml-snakeyaml:5.0.13-ryze.1`
   - `ryze-libdatachannel-java:0.24.6.0-ryze.1`,
     incluyendo JAR JNI `linux-glibc-x86_64`.
   Todos bajo el namespace **propio** `io.github.ryzemcbe`.
   Ver `vendor-central/`, `docs/central-upstream-sources.md`,
   y el workflow `publish-central-vendor.yml`.
5. Solo cuando los cinco POM/JAR propios ya estén publicados, probar,
   firmar y enviar `engine-server:0.14.3-beta.1` con
   `publish-central-server.yml`. El deployment es USER_MANAGED:
   **Publish** se hace manualmente en Sonatype.
6. Comprobar HTTP 200 de `engine-server-0.14.3-beta.1.pom`
   y `engine-server-0.14.3-beta.1.jar`, y ejecutar el build
   `-PengineMavenOnly=true` de [HCF PR #67](https://github.com/RyzeMCBE/HCF/pull/67)
   en un consumidor limpio con solo Maven Central.

Nunca considerar el CI verde con una dependencia `--pending` como prueba
de que esa biblioteca está publicada. La puerta de publicación de Engine
rechaza cualquier POM que no se resuelva públicamente.

## Firma GPG original (sin regenerar ni compartir claves)

Identidad: `RyzeMCBE Development Studio`

Key ID: `32F428403DE9E4D8`

Fingerprint esperado: `0DE435F45DAAE3CCB759E01B32F428403DE9E4D8`

GitHub: configurar el entorno **maven-central** en
`RyzeMCBE/Engine`, con los secretos:
`GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`,
`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`.
Reutilizar los valores originales ya disponibles en el VPS; nunca
enviarlos por chats, documentación o comentarios de PR.
No generar una firma alternativa.

Después de fusionar el PR validado en `main`, abrir
[Actions](https://github.com/RyzeMCBE/Engine/actions) y ejecutar
primero `Maven Central - Engine original-source dependencies`
con `operation=verify-signature`; también usar
`Maven Central - Engine Server` con esa misma operación.
Ambas pruebas verifican huella y firma sin subir archivos.
La subida requiere escoger `upload` y escribir la confirmación exacta
mostrada en la pantalla del workflow.

No enviar artefactos si falla cualquier verificación. Revisar el
deployment USER_MANAGED en
https://central.sonatype.com/publishing/deployments antes de pulsar Publish.

## Puertas técnicas locales y GitHub Actions

```bash
# Modo opt-in de publicación; jamás modifica el ejecutable normal.
./gradlew :server:generatePomFileForMavenPublication \
  -PcentralRelease=true --no-daemon
python3 .github/scripts/verify-central-server-pom.py
python3 .github/scripts/audit-central-resolution.py \
  server/build/publications/maven/pom-default.xml
```

`central-upstream-source-build.yml` compila fuentes originales
con SHA fijo; `central-vendor-candidates.yml` genera los cinco
módulos Java (sin publicar); `central-rtc-native.yml` comprueba el
JAR JNI original Linux x86_64 y el empaquetado equivalente con licencia.

El workflow final de Engine comprueba POM, transitividad mediante Maven
aislado y mirror exclusivo de Central, JAR, sources, Javadoc y firmas
GPG. Una prueba de integración de HCF y de arranque de NetherNet en
servidor real sigue siendo necesaria para certificar ejecución de plugins.

## Comprobación final pública

```bash
base="https://repo.maven.apache.org/maven2/io/github/ryzemcbe/engine-server/0.14.3-beta.1"
curl -fsS -o /dev/null -w 'POM HTTP %{http_code}\n' "$base/engine-server-0.14.3-beta.1.pom"
curl -fsS -o /dev/null -w 'JAR HTTP %{http_code}\n' "$base/engine-server-0.14.3-beta.1.jar"
```

Ambas respuestas deben indicar HTTP 200. Una vez pública y verificada
la dependencia, actualizar README para desarrolladores con estas
coordenadas. No anunciarla antes.
