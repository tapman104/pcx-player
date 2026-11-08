import os
import re
import json
import glob
import subprocess
import xml.etree.ElementTree as ET

def generate_libraries_metadata():
    print("Running gradle dependencies to resolve releaseRuntimeClasspath...")
    gradlew = os.path.join(os.path.dirname(__file__), "..", "gradlew.bat")
    gradlew = os.path.abspath(gradlew)
    cmd = [gradlew, "app:dependencies", "--configuration", "releaseRuntimeClasspath"]
    out = subprocess.check_output(cmd, text=True, encoding="utf-8", errors="replace")

    # Extract all resolved coordinates (group, artifact, version)
    resolved_deps = {}
    for line in out.splitlines():
        # e.g. +--- androidx.activity:activity:1.7.0 -> 1.9.3
        # or   +--- org.jetbrains.kotlin:kotlin-stdlib:2.0.21
        m = re.search(r'([a-zA-Z0-9_\.\-]+):([a-zA-Z0-9_\.\-]+):([0-9\.\-A-Za-z_]+)', line)
        if m:
            group, artifact, version = m.group(1), m.group(2), m.group(3)
            m_arrow = re.search(r'->\s+([0-9\.\-A-Za-z_]+)', line)
            if m_arrow:
                version = m_arrow.group(1)
            resolved_deps[(group, artifact)] = version

    print(f"Found {len(resolved_deps)} unique (group, artifact) dependencies.")

    # Index cached POM files in ~/.gradle/caches/modules-2/files-2.1
    gradle_cache = os.path.expanduser('~/.gradle/caches/modules-2/files-2.1')
    pom_map = {}
    for p in glob.glob(f'{gradle_cache}/**/*.pom', recursive=True):
        norm = os.path.normpath(p)
        parts = norm.split(os.sep)
        try:
            idx = parts.index('files-2.1')
            grp = parts[idx+1]
            art = parts[idx+2]
            ver = parts[idx+3]
            pom_map[(grp, art, ver)] = norm
        except Exception:
            pass

    print(f"Found {len(pom_map)} POMs in Gradle cache.")

    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}

    def get_text(elem, xpath):
        node = elem.find(xpath, ns)
        if node is None:
            node = elem.find(xpath.replace('m:', ''))
        return node.text.strip() if (node is not None and node.text) else None

    libraries = []
    seen_keys = set()

    for (grp, art), ver in sorted(resolved_deps.items()):
        pom_path = pom_map.get((grp, art, ver))
        name = art
        developer = ""
        license_name = "Unknown"

        if pom_path and os.path.exists(pom_path):
            try:
                root = ET.parse(pom_path).getroot()
                pom_name = get_text(root, 'm:name')
                if pom_name and not pom_name.startswith("$"):
                    name = pom_name

                # Developer / Organization
                org_name = get_text(root, 'm:organization/m:name')
                dev_name = get_text(root, 'm:developers/m:developer/m:name')
                dev_org = get_text(root, 'm:developers/m:developer/m:organization')
                developer = org_name or dev_name or dev_org or ""

                # Licenses
                lics = []
                for lic in root.findall('.//m:license', ns) or root.findall('.//license'):
                    l_name = get_text(lic, 'm:name') or get_text(lic, 'name')
                    if l_name:
                        lics.append(l_name)
                if lics:
                    license_name = " / ".join(lics)
            except Exception as e:
                pass

        if not developer:
            if grp.startswith("androidx"):
                developer = "The Android Open Source Project"
            elif grp.startswith("org.jetbrains.kotlin"):
                developer = "JetBrains"
            elif grp.startswith("com.google"):
                developer = "Google"
            elif grp.startswith("com.squareup"):
                developer = "Square"
            else:
                developer = grp

        # Normalize common license strings
        if "Apache" in license_name:
            license_name = "Apache 2.0"
        elif "MIT" in license_name:
            license_name = "MIT"
        elif "BSD" in license_name:
            license_name = "BSD"

        # Unique key based on name and version
        key = (name.lower(), ver)
        if key not in seen_keys:
            seen_keys.add(key)
            libraries.append({
                "name": name,
                "version": ver,
                "developer": developer,
                "license": license_name
            })

    # Add mpv-android direct dependency
    libraries.append({
        "name": "mpv-android",
        "version": "1.0.0",
        "developer": "mpv-android contributors",
        "license": "LGPL-2.1 / GPL-2.0"
    })

    # Sort alphabetically by library name
    libraries.sort(key=lambda x: x["name"].lower())

    out_dir = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")
    out_dir = os.path.abspath(out_dir)
    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, "libraries.json")

    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(libraries, f, indent=2, ensure_ascii=False)

    print(f"Successfully generated {len(libraries)} libraries to {out_path}")

if __name__ == "__main__":
    generate_libraries_metadata()

