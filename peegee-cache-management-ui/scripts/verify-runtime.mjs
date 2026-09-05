const expectedNode = '22.22.2';

if (process.versions.node !== expectedNode) {
  process.stderr.write(
    `The UI verification gate requires Node ${expectedNode}, but npm resolved ${process.execPath} `
      + `(Node ${process.versions.node}). Run the Maven gate or prepend this module's pinned node directory to PATH.\n`,
  );
  process.exitCode = 1;
}
