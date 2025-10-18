# Dota 2 Ability Draft Parser

A tool to parse Dota 2 Ability Draft replay files (.dem) and generate HTML visualizations of the draft timeline.

## Features

- Extracts complete draft data from replay files
- Automatically resolves hero and ability names from replay data (with no external dependencies)
- Generates HTML visualizations with chronological draft timeline

## Requirements

- Python 3.7+
- Java 17+ (for running the parser JAR)
- Maven (for building from source)

## Building

To build the parser from source:

```bash
# Requires Maven and Java 17+
mvn -f pom.xml clean package
```

The script will automatically find the JAR in `target/` - no need to copy it!

## Usage

Basic usage:

```bash
./parse_draft.py /path/to/replay.dem
```

This will generate `replay.html` in the same directory as the replay file.

### Options

```bash
./parse_draft.py [OPTIONS] REPLAY

Arguments:
  REPLAY                Path to .dem replay file

Options:
  -o, --output PATH     Output HTML file (default: replay_name.html)
  --jar PATH           Path to JAR file (default: auto-detect from target/ or current dir)
  -h, --help           Show help message
```

### Examples

```bash
# Generate HTML with default name
./parse_draft.py match_12345.dem

# Specify output file
./parse_draft.py match_12345.dem -o draft_analysis.html

# Use custom JAR path
./parse_draft.py match_12345.dem --jar /path/to/parser.jar

# Try the included sample
./parse_draft.py examples/8497319569.dem
```

A sample replay and its generated HTML output are included in the `examples/` folder.

## Output

The tool generates a single HTML file containing:

- **Hero Pool**: All heroes available in the draft (with proper names like "Shadow Fiend", "Anti-Mage")
- **Ability Pool**: All abilities available in the draft (with proper names like "Tinker Laser", "Blink Strike")
- **Draft Timeline**: Chronological sequence of all hero and ability picks with:
  - Pick number (#1, #2, etc.)
  - Timestamp (MM:SS)
  - Pick type (HERO or ABILITY)
  - Player (team and position)
  - Name of picked hero/ability
  - Internal ID for reference
- **Statistics**: Summary boxes showing pool sizes and pick counts per team for sanity check

## Project Structure

```
dota2-ad-parser/
├── parse_draft.py                  # Python script
├── examples/
│   ├── 8497319569.dem             # Sample replay
│   └── 8497319569.html            # Sample output
├── src/
│   └── main/
│       └── java/
│           └── dev/
│               └── dota2ad/
│                   └── clarity/
│                       └── DraftExtractor.java    # Java source
├── target/
│   └── clarity-ad-parser-1.0-SNAPSHOT-shaded.jar  # Built JAR (auto-detected)
├── pom.xml                         # Maven configuration
└── README.md
```

The script automatically detects the JAR in `target/` after building.

## How It Works

1. **Extract**: The Java parser (using Clarity library) reads the .dem file and extracts:
   - Ability pool (draft_ability_id)
   - Hero pool (hero_id)
   - Pick order with timestamps
   - Final ability assignments (player_slot, ability_slot)
   - Hero and ability names from entity class names

2. **Format**: The Python script formats entity names:
   - `CDOTA_Unit_Hero_Shadow_Fiend` → "Shadow Fiend"
   - `CDOTA_Ability_Tinker_Laser` → "Tinker Laser"

3. **Generate**: Creates a single HTML file with:
   - Embedded CSS styling
   - Chronological draft timeline
   - Clean, responsive layout

**No external dependencies needed** - all hero and ability names are extracted directly from the replay!

## Limitations

- **Name Resolution**: The parser can only resolve human-readable names (e.g., "Shadow Fiend", "Tinker Laser") for heroes and abilities that were **actually picked** during the draft. Pool items that were available but not picked will only display their internal IDs.

  This is because names are extracted from entity class names that are only created when a hero/ability is picked. Unpicked pool items only have their numeric IDs available in the replay data.

  **Workaround**: You can build a complete ID-to-name mapping by parsing multiple replays and aggregating the mappings across different drafts where different heroes/abilities were picked.

## Dependencies

This tool uses:
- [Clarity](https://github.com/skadistats/clarity) - Dota 2 replay parser (Apache License 2.0)
