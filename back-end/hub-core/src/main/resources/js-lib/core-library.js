
function executeCommands(oaiDoc, commands) {
    console.debug("[core-library] Entering executeCommands");
    var library = new OAI.OasLibraryUtils();
    var oaiDocJSObj = JSON.parse(oaiDoc);
    var document = library.createDocument(oaiDocJSObj);
    
    if (commands) {
        var numCmds = commands.length;
        console.debug("[core-library] Executing " + numCmds + " OAS commands.");
        for (var i = 0; i < numCmds; i++) {
            var cmd = commands[i];
            console.debug("[core-library] Executing command [" + i + "]");
            try {
                cmd = JSON.parse(cmd);
                cmd = OAI_commands.MarshallUtils.unmarshallCommand(cmd);
                cmd.execute(document);
            } catch (e) {
                console.error(e);
            }
        }
    }

    return JSON.stringify(library.writeNode(document), null, 2);
}
