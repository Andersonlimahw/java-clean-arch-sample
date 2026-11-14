#!/usr/bin/env python3
"""Scaffold an owner-scoped CRUD module for the payments platform.

Usage:
    python3 -I .agents/skills/create-crud/scripts/scaffold.py <resource> [--singular <name>]
                                                                [--root <repo>] [--dry-run]

<resource> is the plural, kebab-case resource name, for example `products` or `order-items`.

The script renders the templates next to it into a new Spring Modulith module and wires the
four places the existing code keeps as explicit lists: the ArchUnit web-boundary rule, the
security matchers, the outbox routing keys, and the Postman collection. Every precondition is
checked before the first write, so a failed run leaves the working tree untouched.

Standard library only. No network access.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

SKILL_DIR = Path(__file__).resolve().parent.parent
TEMPLATE_DIR = SKILL_DIR / "templates"

RESOURCE_PATTERN = re.compile(r"^[a-z][a-z0-9]*(-[a-z][a-z0-9]*)*$")
TOKEN_PATTERN = re.compile(r"__[A-Za-z]+(?:_[A-Za-z]+)*__")
RESERVED_MODULES = {"shared", "identity", "payments", "notifications"}
# A module named after one of these would produce an invalid or misleading Java package.
JAVA_RESERVED = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
    "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
    "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
    "new", "package", "private", "protected", "public", "record", "return", "short", "static",
    "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try",
    "var", "void", "volatile", "while", "true", "false", "null",
}

ARCH_TEST = Path("src/test/java/com/muse/payments/ArchitectureTest.java")
SECURITY_CONFIG = Path("src/main/java/com/muse/payments/shared/security/SecurityConfig.java")
OUTBOX_RELAY = Path("src/main/java/com/muse/payments/shared/infrastructure/OutboxRelayService.java")
MIGRATIONS = Path("src/main/resources/db/migration")
POSTMAN = Path("src/test/postman/payments-v1.postman_collection.json")
MODULES = Path("src/main/java/com/muse/payments")

ARCH_ANCHOR = '"com.muse.payments.identity.domain..",'
SECURITY_ANCHOR = ".anyRequest().authenticated()"
RELAY_ANCHOR = 'default -> "payment." + eventType.toLowerCase();'


class ScaffoldError(Exception):
    """A precondition failed. Nothing has been written when this is raised from preflight."""


def singularize(word: str) -> str | None:
    if word.endswith("ies") and len(word) > 3:
        return word[:-3] + "y"
    for suffix in ("sses", "xes", "ches", "shes"):
        if word.endswith(suffix):
            return word[:-2]
    if word.endswith("s") and not word.endswith("ss") and len(word) > 1:
        return word[:-1]
    return None


def pascal(words: list[str]) -> str:
    return "".join(word.capitalize() for word in words)


def camel(words: list[str]) -> str:
    return words[0] + "".join(word.capitalize() for word in words[1:])


def build_names(resource: str, singular: str | None) -> dict[str, str]:
    if not RESOURCE_PATTERN.match(resource):
        raise ScaffoldError(
            f"resource '{resource}' must be plural kebab-case, for example 'products' or 'order-items'")
    plural_words = resource.split("-")

    if singular is None:
        last = singularize(plural_words[-1])
        if last is None:
            raise ScaffoldError(
                f"cannot derive the singular of '{resource}'; pass --singular <kebab-case-name>")
        singular_words = plural_words[:-1] + [last]
    else:
        if not RESOURCE_PATTERN.match(singular):
            raise ScaffoldError(f"--singular '{singular}' must be kebab-case, for example 'order-item'")
        singular_words = singular.split("-")

    if singular_words == plural_words:
        raise ScaffoldError("singular and plural names must differ; the generated class names would collide")

    package = "".join(plural_words)
    if package in RESERVED_MODULES:
        raise ScaffoldError(f"'{package}' is an existing module; choose another resource name")
    if package in JAVA_RESERVED or camel(singular_words) in JAVA_RESERVED:
        raise ScaffoldError(f"'{resource}' maps to a Java reserved word; choose another resource name")

    label = " ".join(singular_words)
    return {
        "__PKG__": package,
        "__Entity__": pascal(singular_words),
        "__Entities__": pascal(plural_words),
        "__entity__": camel(singular_words),
        "__TABLE__": "_".join(plural_words),
        "__PATH__": resource,
        "__LABEL__": label,
        "__Label__": label.capitalize(),
        "__EVENT_KEY__": "-".join(singular_words),
        "__QUEUE__": f"{resource}.events",
    }


def render(text: str, names: dict[str, str]) -> str:
    for token, value in names.items():
        text = text.replace(token, value)
    return text


def repo_root(explicit: str | None) -> Path:
    if explicit:
        root = Path(explicit).resolve()
    else:
        result = subprocess.run(
            ["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=False)
        if result.returncode != 0:
            raise ScaffoldError("not inside a git checkout; pass --root <repository root>")
        root = Path(result.stdout.strip())
    if not (root / "pom.xml").is_file() or not (root / MODULES).is_dir():
        raise ScaffoldError(f"{root} is not the payments platform repository root")
    return root


def next_migration_version(root: Path) -> int:
    versions = []
    for path in (root / MIGRATIONS).glob("V*__*.sql"):
        match = re.match(r"V(\d+)__", path.name)
        if match:
            versions.append(int(match.group(1)))
    if not versions:
        raise ScaffoldError(f"no Flyway migrations found under {MIGRATIONS}")
    return max(versions) + 1


def plan_insert(root: Path, relative: Path, anchor: str, new_lines: list[str]) -> tuple[Path, str]:
    """Return the file and its new content with new_lines inserted before the anchor line."""
    path = root / relative
    if not path.is_file():
        raise ScaffoldError(f"{relative} not found; the wiring step needs it")
    lines = path.read_text(encoding="utf-8").split("\n")
    matches = [index for index, line in enumerate(lines) if anchor in line]
    if len(matches) != 1:
        raise ScaffoldError(
            f"{relative}: expected exactly one line containing `{anchor}`, found {len(matches)}. "
            "The file changed shape; wire this step by hand as described in SKILL.md.")
    index = matches[0]
    indent = lines[index][: len(lines[index]) - len(lines[index].lstrip())]
    for new_line in new_lines:
        if any(new_line in line for line in lines):
            raise ScaffoldError(f"{relative} already contains `{new_line}`")
    lines[index:index] = [indent + new_line for new_line in new_lines]
    return path, "\n".join(lines)


def plan_templates(root: Path, names: dict[str, str]) -> list[tuple[Path, str]]:
    planned = []
    for template in sorted(TEMPLATE_DIR.rglob("*.tmpl")):
        relative = render(str(template.relative_to(TEMPLATE_DIR))[: -len(".tmpl")], names)
        target = root / relative
        if target.exists():
            raise ScaffoldError(f"{relative} already exists; refusing to overwrite")
        content = render(template.read_text(encoding="utf-8"), names)
        for candidate in (relative, content):
            leftover = TOKEN_PATTERN.search(candidate)
            if leftover:
                raise ScaffoldError(f"unrendered token {leftover.group(0)} in {relative}")
        planned.append((target, content))
    if not planned:
        raise ScaffoldError(f"no templates found under {TEMPLATE_DIR}")
    return planned


def js(lines: list[str]) -> dict:
    return {"type": "text/javascript", "exec": lines}


def request_item(name: str, method: str, url: str, tests: list[str], body: str | None = None,
                 description: str = "", auth_none: bool = False) -> dict:
    request: dict = {"method": method, "header": [], "url": url, "description": description}
    if body is not None:
        request["header"].append({"key": "Content-Type", "value": "application/json"})
        request["body"] = {"mode": "raw", "raw": body, "options": {"raw": {"language": "json"}}}
    if auth_none:
        request["auth"] = {"type": "noauth"}
    return {"name": name, "request": request, "event": [{"listen": "test", "script": js(tests)}]}


def postman_folder(names: dict[str, str], number: str) -> dict:
    entity = names["__entity__"]
    label = names["__LABEL__"]
    base = "{{baseUrl}}/api/v1/" + names["__PATH__"]
    id_var = f"{entity}Id"
    version_var = f"{entity}Version"
    name_var = f"{entity}Name"
    schema = json.dumps({
        "type": "object",
        "required": ["id", "userId", "name", "description", "createdAt", "updatedAt", "version"],
        "properties": {
            "id": {"type": "string", "pattern": "^[0-9a-fA-F-]{36}$"},
            "userId": {"type": "string", "pattern": "^[0-9a-fA-F-]{36}$"},
            "name": {"type": "string", "minLength": 1, "maxLength": 120},
            "description": {"type": ["string", "null"]},
            "createdAt": {"type": "string", "minLength": 1},
            "updatedAt": {"type": "string", "minLength": 1},
            "version": {"type": "integer", "minimum": 0},
        },
    }, separators=(",", ":"))
    problem = ('pm.test("Problem RFC 7807", () => { pm.expect(pm.response.headers.get("Content-Type"))'
               '.to.match(/application\\/problem\\+json/i); const b = pm.response.json(); '
               'pm.expect(b.status).to.eql(pm.response.code); pm.expect(b.title).to.be.a("string"); });')
    owner = ('pm.test("Pertence ao usuário", () => pm.expect(pm.response.json().userId)'
             '.to.eql(pm.collectionVariables.get("userId")));')
    save = (f'if (pm.response.code === 200 || pm.response.code === 201) {{ const b = pm.response.json(); '
            f'if (typeof b.id === "string") pm.collectionVariables.set("{id_var}", b.id); '
            f'if (Number.isInteger(b.version)) pm.collectionVariables.set("{version_var}", String(b.version)); }}')

    items = [
        request_item(
            f"01 · Criar {label}", "POST", base,
            ['pm.test("HTTP 201", () => pm.response.to.have.status(201));',
             f'pm.test("Payload conforme contrato", () => pm.response.to.have.jsonSchema({schema}));',
             owner,
             f'pm.test("Location aponta para o recurso", () => pm.expect(pm.response.headers.get("Location"))'
             f'.to.eql("/api/v1/{names["__PATH__"]}/" + pm.response.json().id));',
             'pm.test("Versão inicial 0", () => pm.expect(pm.response.json().version).to.eql(0));',
             save],
            body='{\n  "name": "{{' + name_var + '}}",\n  "description": "Exemplo Postman"\n}',
            description="Cria o recurso do usuário autenticado e preserva id e version."),
        request_item(
            f"02 · Consultar {label}", "GET", base + "/{{" + id_var + "}}",
            ['pm.test("HTTP 200", () => pm.response.to.have.status(200));',
             f'pm.test("Payload conforme contrato", () => pm.response.to.have.jsonSchema({schema}));',
             f'pm.test("Mesmo id", () => pm.expect(pm.response.json().id).to.eql(pm.collectionVariables.get("{id_var}")));',
             owner]),
        request_item(
            f"03 · Listar {label} — descendente", "GET", base + "?page=0&size=20&sort=createdAt,desc",
            ['pm.test("HTTP 200", () => pm.response.to.have.status(200));',
             'pm.test("Página conforme contrato", () => { const b = pm.response.json(); '
             'pm.expect(b.items).to.be.an("array"); pm.expect(b.page).to.eql(0); pm.expect(b.size).to.eql(20); '
             'pm.expect(b.sort).to.eql("createdAt,desc"); pm.expect(b.totalElements).to.be.at.least(1); });',
             f'pm.test("Contém o recurso criado", () => pm.expect(pm.response.json().items.map(i => i.id))'
             f'.to.include(pm.collectionVariables.get("{id_var}")));']),
        request_item(
            f"04 · Atualizar {label}", "PUT", base + "/{{" + id_var + "}}",
            ['pm.test("HTTP 200", () => pm.response.to.have.status(200));',
             f'pm.test("Payload conforme contrato", () => pm.response.to.have.jsonSchema({schema}));',
             f'pm.test("Versão incrementada", () => pm.expect(pm.response.json().version)'
             f'.to.eql(Number(pm.collectionVariables.get("{version_var}")) + 1));',
             'pm.test("Nome atualizado", () => pm.expect(pm.response.json().name).to.match(/ atualizado$/));',
             save],
            body='{\n  "name": "{{' + name_var + '}} atualizado",\n  "description": "Alterado",\n'
                 '  "version": {{' + version_var + '}}\n}',
            description="Envia a version lida por último; a resposta traz a version seguinte."),
        request_item(
            f"05 · Atualizar com version antiga", "PUT", base + "/{{" + id_var + "}}",
            ['pm.test("HTTP 409", () => pm.response.to.have.status(409));', problem],
            body='{\n  "name": "{{' + name_var + '}} conflito",\n  "description": null,\n  "version": 0\n}',
            description="A version 0 já foi consumida pela atualização anterior: conflito esperado."),
        request_item(
            f"06 · Nome inválido", "POST", base,
            ['pm.test("HTTP 400", () => pm.response.to.have.status(400));', problem,
             'pm.test("Campo name apontado", () => pm.expect(pm.response.json().invalidParams).to.have.property("name"));'],
            body='{\n  "name": " ",\n  "description": null\n}'),
        request_item(
            f"07 · Sem bearer token", "GET", base,
            ['pm.test("HTTP 401", () => pm.response.to.have.status(401));', problem],
            auth_none=True),
        request_item(
            f"08 · Remover {label}", "DELETE", base + "/{{" + id_var + "}}",
            ['pm.test("HTTP 204", () => pm.response.to.have.status(204));',
             'pm.test("Corpo vazio", () => pm.expect(pm.response.text()).to.eql(""));']),
        request_item(
            f"09 · Consultar {label} removido", "GET", base + "/{{" + id_var + "}}",
            ['pm.test("HTTP 404", () => pm.response.to.have.status(404));', problem]),
    ]
    items[0]["event"].insert(0, {"listen": "prerequest", "script": js([
        f'pm.collectionVariables.set("{name_var}", "Postman {label} " + Date.now());'])})
    return {
        "name": f"{number} · {names['__Entities__']}",
        "description": f"CRUD de {label}: criar, consultar, listar, atualizar com version, erros e remover.",
        "item": items,
    }


def plan_postman(root: Path, names: dict[str, str]) -> tuple[Path, str, str]:
    path = root / POSTMAN
    if not path.is_file():
        raise ScaffoldError(f"{POSTMAN} not found")
    collection = json.loads(path.read_text(encoding="utf-8"))
    folders = collection.get("item", [])
    if not folders:
        raise ScaffoldError(f"{POSTMAN} has no folders")
    route = f"/api/v1/{names['__PATH__']}"
    if route + '"' in path.read_text(encoding="utf-8") or any(
            folder.get("name", "").endswith(f"· {names['__Entities__']}") for folder in folders):
        raise ScaffoldError(f"{POSTMAN} already has requests for {route}")

    # The last folder ends the session, so the new folder goes right before it and takes its number.
    last = folders[-1]
    match = re.match(r"^(\d+)( · .*)$", last.get("name", ""))
    if not match:
        raise ScaffoldError(f"{POSTMAN}: last folder '{last.get('name')}' is not named 'NN · title'")
    number = match.group(1)
    renumbered = f"{int(number) + 1:0{len(number)}d}{match.group(2)}"
    last["name"] = renumbered
    folders.insert(len(folders) - 1, postman_folder(names, number))

    variables = collection.setdefault("variable", [])
    existing = {variable.get("key") for variable in variables}
    for key in (f"{names['__entity__']}Id", f"{names['__entity__']}Version", f"{names['__entity__']}Name"):
        if key in existing:
            raise ScaffoldError(f"{POSTMAN} already defines the collection variable '{key}'")
        variables.append({"key": key, "value": ""})

    note = f"folder '{number} · {names['__Entities__']}' added; session folder renamed to '{renumbered}'"
    return path, json.dumps(collection, ensure_ascii=False, indent=2) + "\n", note


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Scaffold an owner-scoped CRUD module.")
    parser.add_argument("resource", help="plural kebab-case resource name, e.g. products or order-items")
    parser.add_argument("--singular", help="kebab-case singular name when it cannot be derived")
    parser.add_argument("--root", help="repository root (default: git toplevel of the current directory)")
    parser.add_argument("--dry-run", action="store_true", help="print the plan and write nothing")
    args = parser.parse_args(argv)

    try:
        names = build_names(args.resource, args.singular)
        root = repo_root(args.root)
        if (root / MODULES / names["__PKG__"]).exists():
            raise ScaffoldError(f"module '{names['__PKG__']}' already exists")
        version = next_migration_version(root)
        names["__MIGRATION__"] = f"V{version}__{names['__TABLE__']}"

        writes = plan_templates(root, names)
        entity, key, pkg = names["__Entity__"], names["__EVENT_KEY__"], names["__PKG__"]
        edits = [
            plan_insert(root, ARCH_TEST, ARCH_ANCHOR, [f'"com.muse.payments.{pkg}.domain..",']),
            plan_insert(root, SECURITY_CONFIG, SECURITY_ANCHOR, [
                f'.requestMatchers("/api/v1/{names["__PATH__"]}", "/api/v1/{names["__PATH__"]}/**")'
                '.hasAnyRole("USER", "ADMIN")']),
            plan_insert(root, OUTBOX_RELAY, RELAY_ANCHOR, [
                f'case "{entity}Created" -> "{key}.created";',
                f'case "{entity}Updated" -> "{key}.updated";',
                f'case "{entity}Deleted" -> "{key}.deleted";']),
        ]
        postman_path, postman_content, postman_note = plan_postman(root, names)
    except ScaffoldError as error:
        print(f"scaffold: {error}", file=sys.stderr)
        return 1

    action = "would create" if args.dry_run else "created"
    for target, content in writes:
        if not args.dry_run:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
        print(f"{action}  {target.relative_to(root)}")
    action = "would edit" if args.dry_run else "edited"
    for target, content in edits + [(postman_path, postman_content)]:
        if not args.dry_run:
            target.write_text(content, encoding="utf-8")
        print(f"{action}   {target.relative_to(root)}")

    print()
    print(f"module      com.muse.payments.{pkg}  ({entity})")
    print(f"route       /api/v1/{names['__PATH__']}")
    print(f"migration   V{version}__{names['__TABLE__']}.sql")
    print(f"events      {entity}Created|Updated|Deleted -> {key}.created|updated|deleted -> {names['__QUEUE__']}")
    print(f"postman     {postman_note}")
    print()
    print("Next: adapt the fields, update the docs, and run the gate. See SKILL.md steps 3 to 6.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
