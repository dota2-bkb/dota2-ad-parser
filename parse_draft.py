#!/usr/bin/env python3
"""
Dota 2 Ability Draft Parser
Parses .dem replay files and generates HTML visualization of the draft timeline.
No external dependencies required - formats names directly from replay data.
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path


def extract_draft_data(dem_file, jar_path):
    """
    Extract draft data from .dem file using Java parser.
    Returns parsed JSON data.
    """
    try:
        result = subprocess.run(
            ['java', '-jar', str(jar_path), '--in', str(dem_file), '--json'],
            capture_output=True,
            text=True,
            check=True
        )
        return json.loads(result.stdout)
    except subprocess.CalledProcessError as e:
        print(f"Error running parser: {e.stderr}", file=sys.stderr)
        sys.exit(1)
    except json.JSONDecodeError as e:
        print(f"Error parsing JSON output: {e}", file=sys.stderr)
        sys.exit(1)
    except FileNotFoundError:
        print("Error: Java not found. Please install Java 17 or later.", file=sys.stderr)
        sys.exit(1)


def format_name(key):
    """
    Format snake_case key to Title Case name.
    Examples:
      tinker_laser -> Tinker Laser
      shadow_fiend -> Shadow Fiend
      pangolier -> Pangolier
    """
    return key.replace("_", " ").title()


def get_ability_info(draft_ability_id, ability_mappings):
    """
    Get ability name from draft ability ID.
    Returns: ability_name
    """
    for mapping in ability_mappings:
        if mapping.get("draft_ability_id") == draft_ability_id:
            ability_key = mapping.get("ability_key", "")
            if ability_key:
                return format_name(ability_key)

    # Fallback
    return f"Ability {draft_ability_id}"


def build_ult_set(ability_mappings):
    """Build set of ability_ids that are ultimates (ability_slot == 3) from ability_mappings."""
    return {m["draft_ability_id"] for m in ability_mappings if m.get("ability_slot") == 3}


def get_hero_name(hero_id, hero_picks):
    """Get hero name from hero_id using hero_picks data."""
    for hero_pick in hero_picks:
        if hero_pick.get("hero_id") == hero_id:
            hero_key = hero_pick.get("hero_key")
            if hero_key:
                return format_name(hero_key)
    return f"Hero {hero_id}"


def get_player_color(player_slot):
    """Get color based on team (radiant=green, dire=red)."""
    return "#2ecc71" if player_slot < 128 else "#e74c3c"


def get_player_label(player_slot):
    """Get player label from slot."""
    if player_slot >= 128:
        team = "Dire"
        position = player_slot - 128
    else:
        team = "Radiant"
        position = player_slot
    return f"{team} #{position}"


def generate_html(draft_data, dem_file):
    """Generate HTML visualization of the draft timeline."""
    pool_items = draft_data.get('pool_items', [])
    hero_pool = draft_data.get('hero_pool', [])
    picks = draft_data.get('picks', [])
    hero_picks = draft_data.get('hero_picks', [])
    ability_mappings = draft_data.get('ability_mappings', [])

    # Combine hero picks and ability picks into a unified timeline
    combined_picks = []

    # Add hero picks
    for hero_pick in hero_picks:
        combined_picks.append({
            "type": "hero",
            "tick": hero_pick.get("tick", 0),
            "player_slot": hero_pick.get("player_slot", 0),
            "hero_id": hero_pick.get("hero_id", 0),
            "hero_key": hero_pick.get("hero_key", ""),
        })

    # Add ability picks
    for pick in picks:
        combined_picks.append({
            "type": "ability",
            "tick": pick.get("tick", 0),
            "player_slot": pick.get("player_slot", 0),
            "ability_id": pick.get("ability_id", 0),
        })

    # Sort by tick (chronological order)
    combined_picks_sorted = sorted(combined_picks, key=lambda p: p.get("tick", 0))

    # Calculate stats
    ult_ids = build_ult_set(ability_mappings)
    radiant_ability_picks = sum(1 for p in picks if p.get("player_slot", 0) < 128)
    dire_ability_picks = sum(1 for p in picks if p.get("player_slot", 0) >= 128)
    num_ults = len(ult_ids)
    num_basics = len(pool_items) - num_ults

    html_content = f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Draft - {Path(dem_file).stem}</title>
    <style>
        * {{
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }}

        body {{
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
            background: #0a0e27;
            color: #e8e8e8;
            padding: 15px;
            line-height: 1.4;
        }}

        .container {{
            max-width: 1200px;
            margin: 0 auto;
            background: #151932;
            border-radius: 8px;
            box-shadow: 0 4px 20px rgba(0, 0, 0, 0.5);
            padding: 20px;
        }}

        h1 {{
            color: #fff;
            margin-bottom: 5px;
            font-size: 1.8em;
            text-align: center;
            font-weight: 600;
        }}

        .match-info {{
            text-align: center;
            color: #8892b0;
            margin-bottom: 20px;
            padding-bottom: 15px;
            border-bottom: 1px solid #1e2746;
            font-size: 0.9em;
        }}

        h2 {{
            color: #64ffda;
            margin: 20px 0 10px 0;
            font-size: 1.1em;
            font-weight: 600;
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }}

        .pool {{
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
            gap: 6px;
            margin-bottom: 25px;
        }}

        .pool-item {{
            background: #1e2746;
            color: #e8e8e8;
            padding: 6px 8px;
            border-radius: 4px;
            text-align: center;
            font-size: 0.8em;
            border: 1px solid #2a3554;
            transition: all 0.15s;
        }}

        .pool-item:hover {{
            background: #2a3554;
            border-color: #64ffda;
        }}

        .pool-item-ult {{
            border-color: #f1c40f;
            background: #2a2714;
        }}

        .pool-item-ult:hover {{
            border-color: #f1c40f;
            background: #3a3724;
        }}

        .ult-badge {{
            font-size: 0.65em;
            font-weight: 700;
            color: #f1c40f;
            background: rgba(241, 196, 15, 0.15);
            padding: 1px 4px;
            border-radius: 3px;
            vertical-align: middle;
        }}

        .pick.ult-pick {{
            border-left-color: #f1c40f !important;
        }}

        .pool-item-name {{
            margin-bottom: 2px;
            font-weight: 500;
        }}

        .pool-item-ids {{
            font-size: 0.75em;
            opacity: 0.6;
        }}

        .picks-container {{
            display: flex;
            flex-direction: column;
            gap: 4px;
        }}

        .pick {{
            display: flex;
            align-items: center;
            gap: 8px;
            background: #1e2746;
            border-radius: 4px;
            padding: 6px 10px;
            border-left: 3px solid;
            transition: all 0.15s;
            font-size: 0.85em;
        }}

        .pick:hover {{
            background: #2a3554;
            transform: translateX(2px);
        }}

        .pick.hero-pick {{
            background: #2d1f1a;
            border-left-color: #ff6b35;
        }}

        .pick.hero-pick:hover {{
            background: #3d2f2a;
        }}

        .pick.ability-pick {{
            border-left-color: #64ffda;
        }}

        .pick-number {{
            font-weight: 600;
            width: 30px;
            color: #8892b0;
            flex-shrink: 0;
            font-size: 0.9em;
        }}

        .pick-time {{
            font-family: 'Courier New', monospace;
            color: #64ffda;
            width: 45px;
            flex-shrink: 0;
            font-size: 0.8em;
        }}

        .pick-type {{
            font-weight: 600;
            font-size: 0.7em;
            text-transform: uppercase;
            width: 50px;
            flex-shrink: 0;
            padding: 2px 6px;
            border-radius: 3px;
            text-align: center;
            letter-spacing: 0.3px;
        }}

        .pick-type.hero {{
            background: #ff6b35;
            color: #fff;
        }}

        .pick-type.ability {{
            background: #64ffda;
            color: #0a0e27;
        }}

        .pick-player {{
            font-weight: 500;
            width: 100px;
            flex-shrink: 0;
            font-size: 0.85em;
        }}

        .pick-content {{
            flex-grow: 1;
            font-weight: 500;
        }}

        .pick-meta {{
            font-size: 0.75em;
            color: #8892b0;
            background: #0a0e27;
            padding: 2px 8px;
            border-radius: 10px;
            flex-shrink: 0;
        }}

        .stats {{
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
            gap: 10px;
            margin-top: 20px;
            padding-top: 20px;
            border-top: 1px solid #1e2746;
        }}

        .stat-box {{
            background: #1e2746;
            border: 1px solid #2a3554;
            padding: 12px;
            border-radius: 4px;
            text-align: center;
        }}

        .stat-value {{
            font-size: 1.8em;
            font-weight: 700;
            color: #64ffda;
            margin-bottom: 3px;
        }}

        .stat-label {{
            font-size: 0.75em;
            color: #8892b0;
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }}
    </style>
</head>
<body>
    <div class="container">
        <h1>Ability Draft</h1>
        <div class="match-info">
            {Path(dem_file).stem}
        </div>

        <h2>Hero Pool ({len(hero_pool)})</h2>
        <div class="pool">
"""

    # Add hero pool items
    for hero in hero_pool:
        hero_id = hero.get("hero_id", 0)
        hero_name = get_hero_name(hero_id, hero_picks)
        html_content += f'            <div class="pool-item" style="background: #2d1f1a; border-color: #ff6b35;">{hero_name}</div>\n'

    html_content += f"""        </div>

        <h2>Ability Pool ({len(pool_items)})</h2>
        <div class="pool">
"""

    # Add ability pool items
    for pool_item in pool_items:
        draft_ability_id = pool_item.get("ability_id", 0)
        ability_name = get_ability_info(draft_ability_id, ability_mappings)
        is_ult = draft_ability_id in ult_ids
        ult_class = " pool-item-ult" if is_ult else ""
        ult_label = ' <span class="ult-badge">ULT</span>' if is_ult else ""

        html_content += f'''            <div class="pool-item{ult_class}">
                <div class="pool-item-name">{ability_name}{ult_label}</div>
                <div class="pool-item-ids">ID: {draft_ability_id}</div>
            </div>\n'''

    html_content += f"""        </div>

        <h2>Draft Timeline ({len(combined_picks_sorted)})</h2>
        <div class="picks-container">
"""

    # Add combined picks sorted by timestamp
    for idx, pick in enumerate(combined_picks_sorted, 1):
        pick_type = pick.get("type", "unknown")
        player_slot = pick.get("player_slot", 0)
        tick = pick.get("tick", 0)

        player_label = get_player_label(player_slot)
        color = get_player_color(player_slot)

        # Convert tick to time (assuming 30 ticks per second)
        time_seconds = tick / 30
        time_str = f"{int(time_seconds // 60)}:{int(time_seconds % 60):02d}"

        if pick_type == "hero":
            hero_id = pick.get("hero_id", 0)
            hero_key = pick.get("hero_key", "")
            hero_name = format_name(hero_key) if hero_key else f"Hero {hero_id}"
            content = hero_name
            meta = f"Hero ID: {hero_id}"
            css_class = "hero-pick"
        else:  # ability
            draft_ability_id = pick.get("ability_id", 0)
            ability_name = get_ability_info(draft_ability_id, ability_mappings)
            is_ult = draft_ability_id in ult_ids
            ult_suffix = ' <span class="ult-badge">ULT</span>' if is_ult else ""
            content = ability_name + ult_suffix
            meta = f"Ability ID: {draft_ability_id}"
            css_class = "ability-pick ult-pick" if is_ult else "ability-pick"

        html_content += f'''            <div class="pick {css_class}" style="border-left-color: {color}">
                <div class="pick-number">#{idx}</div>
                <div class="pick-time">{time_str}</div>
                <div class="pick-type {pick_type}">{pick_type.upper()}</div>
                <div class="pick-player" style="color: {color}">{player_label}</div>
                <div class="pick-content">{content}</div>
                <div class="pick-meta">{meta}</div>
            </div>
'''

    html_content += f"""        </div>

        <div class="stats">
            <div class="stat-box">
                <div class="stat-value">{len(hero_pool)}</div>
                <div class="stat-label">Heroes in Pool</div>
            </div>
            <div class="stat-box">
                <div class="stat-value">{len(hero_picks)}</div>
                <div class="stat-label">Heroes Picked</div>
            </div>
            <div class="stat-box">
                <div class="stat-value">{len(pool_items)}</div>
                <div class="stat-label">Abilities ({num_basics} Basic / {num_ults} Ult)</div>
            </div>
            <div class="stat-box">
                <div class="stat-value">{len(picks)}</div>
                <div class="stat-label">Ability Picks</div>
            </div>
            <div class="stat-box">
                <div class="stat-value">{radiant_ability_picks}</div>
                <div class="stat-label">Radiant Ability Picks</div>
            </div>
            <div class="stat-box">
                <div class="stat-value">{dire_ability_picks}</div>
                <div class="stat-label">Dire Ability Picks</div>
            </div>
        </div>
    </div>
</body>
</html>
"""

    return html_content


def main():
    parser = argparse.ArgumentParser(
        description='Parse Dota 2 Ability Draft replay and generate HTML visualization'
    )
    parser.add_argument('replay', help='Path to .dem replay file')
    parser.add_argument('-o', '--output', help='Output HTML file (default: replay_name.html)')
    parser.add_argument('--jar', help='Path to clarity-ad-parser JAR (default: auto-detect)')

    args = parser.parse_args()

    # Validate inputs
    dem_file = Path(args.replay)
    if not dem_file.exists():
        print(f"Error: Replay file not found: {dem_file}", file=sys.stderr)
        sys.exit(1)

    # Auto-detect JAR path
    if args.jar:
        jar_path = Path(args.jar)
    else:
        # Try target/clarity-ad-parser-1.0-SNAPSHOT-shaded.jar first (from build)
        script_dir = Path(__file__).parent
        jar_path = script_dir / 'target' / 'clarity-ad-parser-1.0-SNAPSHOT-shaded.jar'

    if not jar_path.exists():
        print(f"Error: JAR file not found: {jar_path}", file=sys.stderr)
        print("Please build the JAR with: mvn package", file=sys.stderr)
        print("Or specify JAR path with: --jar /path/to/jar", file=sys.stderr)
        sys.exit(1)

    # Determine output file
    if args.output:
        output_file = Path(args.output)
    else:
        output_file = dem_file.with_suffix('.html')

    print(f"Parsing replay: {dem_file}")

    # Extract draft data
    print("Extracting draft data...")
    draft_data = extract_draft_data(dem_file, jar_path)

    # Generate HTML
    print("Generating HTML...")
    html = generate_html(draft_data, dem_file)

    # Write output
    with open(output_file, 'w', encoding='utf-8') as f:
        f.write(html)

    print(f"✓ HTML generated: {output_file}")


if __name__ == '__main__':
    main()
