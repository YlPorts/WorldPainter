# Third-party notes

The Bedrock LevelDB compression and chunk serialization approach in \`native/src/lib.rs\` was informed by the MIT-licensed \`osm-to-bedrock\` project by Paul Robello and by public Minecraft Bedrock format documentation. The implementation in this repository is reduced to the needs of the WorldPainter Android exporter.

\`rusty-leveldb\` is used as a dependency for LevelDB storage. See its upstream package for its license and notices.

This repository remains distributed under the WorldPainter GPL-3.0 license terms.
