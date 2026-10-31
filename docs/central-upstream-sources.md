# Preparar bibliotecas de terceros desde sus repositorios originales

Estado: **fuentes identificadas, preparadas para compilación de prueba; no
publicadas**. La publicación bajo `io.github.ryzemcbe` debe conservar
paquetes Java binariamente compatibles, fuentes, licencia, autores y cualquier
NOTICE original. No usar coordenadas originales de otro propietario.

## Fuentes verificadas y SHA inmutable

| Módulo | Fuente original | SHA de referencia | Licencia |
|---|---|---|---|
| Math immutable/API | https://github.com/CloudburstMC/math | `87f24259a74a5ed690251fcc940b97f3fb540e47` | MIT |
| Natives/Zlib | https://github.com/CloudburstMC/Natives | `41826fe18358f7358707dc55e698abc16cdcc49a` | Apache-2.0 |
| RakNet compatible | https://github.com/CloudburstMC/Network | `78a3224e829f2c4bcfe5a298320477d2f46ce6fa` | Apache-2.0 |
| NetherNet | https://github.com/CloudburstMC/Network | `247cdfda6ec02cfef75bbdc9704ad1961245d6fb` | Apache-2.0 |
| Okaeri Configs 5.0.13 | https://github.com/OkaeriPoland/okaeri-configs | `b20e94ea63fc3089bb892318ad305792a72274f9` | MIT |
| libdatachannel-java | https://github.com/opencollab-incubator/libdatachannel-java | `0eb42beb7f7b17e8b383ea848a52d1c2da0c8e7c` | MPL-2.0 |

Los SHA son puntos de partida para validar compatibilidad con los binarios
ya fijados; aún no se ha demostrado equivalencia exacta con todos los snapshots
publicados fuera de Central. La selección de RakNet se corresponde con el árbol
que conserva `org.cloudburstmc.netty.channel.raknet.RakDisconnectReason`.
NO sustituirla por una rama posterior que haya borrado ese tipo.

Los artefactos de terceros necesitan nombres inequívocos bajo
`io.github.ryzemcbe`, preferiblemente:
- `ryze-cloudburst-math-immutable`
- `ryze-nukkit-natives` (incluidas bibliotecas nativas necesarias)
- `ryze-netty-transport-raknet`
- `ryze-netty-transport-nethernet`
- `ryze-okaeri-configs-core` y `ryze-okaeri-configs-yaml-snakeyaml`
- `ryze-libdatachannel-java` y su classifier `linux-glibc-x86_64`

La lista es una propuesta de **identificadores de publicación**, NO coordenadas
existentes. Publicar un proyecto con un groupId diferente sin actualizar sus
POMs y las referencias de Protocol/Engine no resuelve nada. Algunos proyectos
dependen de otros módulos del mismo proyecto; publicarlos también o enlazarlos
correctamente es obligatorio.

## Probar compilación de fuentes

GitHub Actions: `Engine upstream source compatibility`, workflow
`.github/workflows/central-upstream-source-build.yml`.

Este flujo **no publica**, no usa claves de Sonatype y fija el commit con
`git rev-parse HEAD`. Compila los proyectos originales en un runner Linux y
almacena resultados como artefactos temporales para revisión. Los módulos JNI
requieren compilación/pruebas nativas **adicionales** en Linux glibc x86_64.
El simple JAR Java no prueba que WebRTC funcione.

## JetBrains GUI (séptima dependencia)

`com.jetbrains.intellij.java:java-gui-forms-rt:261.24374.191` se usa
en `org.allaymc.server.gui.Dashboard`. No es necesaria para que HCF
compile la API del servidor si se separa una biblioteca *headless* del
ejecutable gráfico. El JAR completo de Engine debe mantener GUI Forms RT
del repositorio original de JetBrains; no redistribuir un binario de JetBrains
bajo nuestra firma sin comprobar expresamente términos y fuentes.

## Antes de permitir Maven Central

1. Verificar que cada fuente coincide con la versión binaria necesaria,
   mediante API ABI, tests de enlace y firmas de clases.
2. Preparar fuentes, Javadoc, POM completo, enlaces de dependencias,
   licencia, NOTICE y firmas GPG.
3. Si se publica código MPL 2.0, incluir fuente accesible y avisos debidos.
4. Comprobar ABI compartido entre los módulos `transport-common` de las dos
   generaciones de Cloudburst. No mezclar clases duplicadas.
5. Ejecutar desde un consumidor limpio con **solo Maven Central** las
   pruebas de compresión, RakNet, NetherNet, conexión de jugadores, NPC,
   scoreboard y HCF.
6. El propietario publicará y aprobará el deployment en Sonatype.

Estado actual: los PR #7 de Protocol y #53 de Engine permanecen
**bloqueados** hasta corregir dependencias y completar estas pruebas.

## Ajustes de compilación original

El proyecto Natives 1.0.3 contiene un perfil Maven Linux heredado que
intenta ejecutar `make` sin que exista Makefile. El flujo de prueba usa
CMake sobre el código nativo original y `-Dantrun.skip=true` para evitar
ese paso antiguo; hay que inspeccionar el JAR y el empaquetado JNI antes de
publicar, incluyendo la prueba de carga de la biblioteca compartida.

El commit Math ahora apunta al árbol de 27 de marzo de 2022,
inmediatamente anterior al snapshot `2.0-20220327.162752-1`, y no a
cambios posteriores de diciembre de 2022.
