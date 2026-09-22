const p=process.argv[2];const fs=require("fs");const path=require("path");
try{console.log(`node:   resolve=%s realpath=%s`, path.resolve(p), fs.realpathSync.native(p))}catch(e){console.log("node:   err",String(e))}
