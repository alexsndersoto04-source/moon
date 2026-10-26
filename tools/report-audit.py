#!/usr/bin/env python3
"""Arma el informe de la auditoria de arranque a partir de los reportes de Gradle.

Lee lo que Gradle deja en el workspace (lint XML + resultados de tests JUnit XML) y
escupe markdown para el issue. Si un archivo no esta, NO se calla: pega el final del
log correspondiente, porque "no hay informe" sin explicacion es exactamente lo que
paso la primera vez que corre esto.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

REPO = os.getcwd()
PRIORITY = {"NewApi", "MissingClass", "Instantiatable", "MissingSuperCall", "WrongCall",
            "UnresolvedClass", "InvalidPackage", "ScrollViewSize", "UnusedResources"}
FATAL = ("fatal", "error")


def tail(path, n):
    try:
        with open(path, "r", errors="replace") as fh:
            return "\n".join(fh.read().splitlines()[-n:])
    except OSError:
        return "(%s no existe)" % path


def lint_section():
    rep = os.path.join(REPO, "app/build/reports/lint-results-debug.xml")
    out = ["### Lint (compatibilidad con minSdk 29)", ""]
    if not os.path.exists(rep):
        out.append("_no se generó el XML de lint — final del log:_")
        out.append("```")
        out.append(tail(os.path.join(REPO, "lint.log"), 25))
        out.append("```")
        return "\n".join(out)
    rows, seen = [], {}
    for it in ET.parse(rep).getroot().findall("issue"):
        iid = it.get("id") or "?"
        sev = (it.get("severity") or "").lower()
        msg = (it.get("message") or "").strip().replace("|", "/")
        loc = it.find("location")
        where = ""
        if loc is not None:
            where = (loc.get("file") or "").replace(REPO + "/", "")
            if loc.get("line"):
                where += ":" + loc["line"]
        seen[iid] = seen.get(iid, 0) + 1
        if iid in PRIORITY or sev in FATAL:
            rows.append("`%s` **%s** — %s  \n    `%s`" % (sev or "?", iid, msg[:240], where))
    if rows:
        out.append("**%d hallazgos relevantes:**" % len(rows))
        out.append("")
        out += ["- " + r for r in rows[:60]]
    else:
        out.append("Sin hallazgos de NewApi/MissingClass/Instantiatable ni errores. "
                   "El crash no es una API inexistente en API 29.")
    if seen:
        out.append("")
        out.append("Totales por regla: " + ", ".join("%s=%d" % kv for kv in sorted(seen.items())[:30]))
    return "\n".join(out)


def tests_section():
    files = glob.glob(os.path.join(REPO, "app/build/test-results/testDebugUnitTest/*.xml"))
    out = ["### Prueba de arranque en la JVM (Robolectric, SDK 29)", ""]
    if not files:
        out.append("_no se generaron resultados de test — final del log:_")
        out.append("```")
        out.append(tail(os.path.join(REPO, "test.log"), 40))
        out.append("```")
        return "\n".join(out)
    ran = failed = 0
    blocks = []
    for f in files:
        root = ET.parse(f).getroot()
        ran += int(root.get("tests") or 0)
        failed += int(root.get("failures") or 0) + int(root.get("errors") or 0)
        for tc in root.findall("testcase"):
            for kind in ("failure", "error"):
                for node in tc.findall(kind):
                    body = (node.get("message") or "") + "\n" + (node.text or "")
                    blocks.append("**%s.%s** `%s`\n```\n%s\n```" % (
                        tc.get("classname", "?").split(".")[-1], tc.get("name", "?"), kind,
                        body.strip()[:3000]))
    if failed:
        out.append("❌ %d de %d pruebas fallaron. Este es el stack del arranque, sin telefono:" % (failed, ran))
        out.append("")
        out += blocks
    elif ran:
        out.append("✅ %d pruebas en verde: `MoonApplication.onCreate` + creacion de `MainActivity` "
                   "no lanzan excepcion en API 29 simulado. El bug entonces NO es de logica de arranque: "
                   "quedan GPU/ROM (RenderThread nativo), MIUI matando el proceso, o algo que solo aparece "
                   "con Wi-Fi Direct/NFC reales." % ran)
    else:
        out.append("(0 pruebas ejecutadas — revisá el filtro --tests)")
    return "\n".join(out)


def main():
    print("## 🔬 Auditoría de arranque de MoonShare")
    print("")
    print("- commit: `%s`" % os.environ.get("GITHUB_SHA", "?"))
    print("")
    print(lint_section())
    print("")
    print(tests_section())


if __name__ == "__main__":
    try:
        main()
    except Exception:
        import traceback
        print("## ⚠️ report-audit.py rompio")
        print("```")
        traceback.print_exc(file=sys.stdout)
        print("```")
        raise SystemExit(1)
