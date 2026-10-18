"""Convert one document in an isolated LibreOffice process; never overwrite the source."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
import zipfile
import xml.etree.ElementTree as ET


def reject_external_calculation(source):
    with zipfile.ZipFile(source) as package:
        if "xl/workbook.xml" not in package.namelist():
            return
        for name in package.namelist():
            if name.startswith(("xl/externalLinks/", "xl/queryTables/")) or name == "xl/connections.xml":
                raise ValueError("External workbook data is not supported for rendering")
            if name.startswith("xl/worksheets/") and name.endswith(".xml"):
                root = ET.fromstring(package.read(name))
                for node in root.iter():
                    if node.tag.rsplit("}", 1)[-1] == "f" and re.search(r"\b(?:WEBSERVICE|DDE|RTD|SQL\.REQUEST)\s*\(", node.text or "", re.I):
                        raise ValueError("External formula evaluation is disabled")


def main():
    parser = argparse.ArgumentParser()
    for option in ("office", "source", "target", "profile"):
        parser.add_argument("--" + option, required=True)
    parser.add_argument("--timeout", type=int, required=True)
    args = parser.parse_args()
    import uno
    import unohelper
    from com.sun.star.beans import PropertyValue
    from com.sun.star.task import XInteractionHandler

    def prop(name, value):
        item = PropertyValue()
        item.Name, item.Value = name, value
        return item

    class AbortInteraction(unohelper.Base, XInteractionHandler):
        def handle(self, request):
            for continuation in request.getContinuations():
                abort = continuation.queryInterface(uno.getTypeByName("com.sun.star.task.XInteractionAbort"))
                if abort:
                    abort.select()
                    return

    source = Path(args.source).resolve()
    reject_external_calculation(source)
    pipe = "documenter_" + uuid.uuid4().hex
    command = [args.office, "-env:UserInstallation=" + Path(args.profile).resolve().as_uri(), "--headless", "--nologo",
               "--nodefault", "--norestore", "--accept=pipe,name=" + pipe + ";urp;StarOffice.ComponentContext"]
    process = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    print("office-stage: started", flush=True)
    document = None
    desktop = None
    try:
        local = uno.getComponentContext()
        resolver = local.ServiceManager.createInstanceWithContext("com.sun.star.bridge.UnoUrlResolver", local)
        deadline = time.monotonic() + args.timeout
        while True:
            if process.poll() is not None:
                raise RuntimeError("Office process exited before UNO connection")
            try:
                remote = resolver.resolve("uno:pipe,name=" + pipe + ";urp;StarOffice.ComponentContext")
                break
            except Exception:
                if time.monotonic() >= deadline:
                    raise TimeoutError("Office UNO startup timed out")
                time.sleep(0.1)
        desktop = remote.ServiceManager.createInstanceWithContext("com.sun.star.frame.Desktop", remote)
        print("office-stage: connected", flush=True)
        document = desktop.loadComponentFromURL(source.as_uri(), "_blank", 0, (
            prop("Hidden", True), prop("ReadOnly", True), prop("Silent", True),
            prop("MacroExecutionMode", uno.getConstantByName("com.sun.star.document.MacroExecMode.NEVER_EXECUTE")),
            prop("UpdateDocMode", uno.getConstantByName("com.sun.star.document.UpdateDocMode.NO_UPDATE")),
            prop("InteractionHandler", AbortInteraction()), prop("PickListEntry", False)))
        if document is None:
            raise ValueError("Office could not load document")
        print("office-stage: loaded", flush=True)
        indexes = 0
        if document.supportsService("com.sun.star.text.TextDocument"):
            # Rebuild TOC from actual headings and actual layout, then update again after page numbers settle.
            for _ in range(2):
                collection = document.getDocumentIndexes()
                indexes = collection.getCount()
                for index in range(indexes):
                    collection.getByIndex(index).update()
            export_filter = "writer_pdf_Export"
        elif document.supportsService("com.sun.star.sheet.SpreadsheetDocument"):
            document.calculateAll()
            for sheet in document.getSheets():
                charts = sheet.getCharts()
                for name in charts.getElementNames():
                    chart = charts.getByName(name).getEmbeddedObject()
                    if hasattr(chart, "refresh"):
                        chart.refresh()
            export_filter = "calc_pdf_Export"
        else:
            raise ValueError("Unsupported Office document service")
        document.storeToURL(Path(args.target).resolve().as_uri(), (
            prop("FilterName", export_filter), prop("Overwrite", False),
            prop("FilterData", (prop("ExportBookmarks", True), prop("ExportFormFields", False)))))
        print("office-stage: exported", flush=True)
        print(json.dumps({"indexesUpdated": indexes, "filter": export_filter}))
    finally:
        if document is not None:
            try:
                document.close(True)
            except Exception:
                document.dispose()
        if desktop is not None:
            try:
                desktop.terminate()
            except Exception:
                pass
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)


if __name__ == "__main__":
    try:
        main()
    except ImportError:
        print("UNO Python runtime is unavailable", file=sys.stderr)
        sys.exit(3)
    except TimeoutError:
        print("Office startup timed out", file=sys.stderr)
        sys.exit(4)
    except Exception as failure:
        print("Office conversion failed: " + type(failure).__name__, file=sys.stderr)
        sys.exit(2)
