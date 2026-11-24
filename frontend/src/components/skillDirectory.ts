/** 目录转换为标准 stored ZIP，保留目录内的相对路径，复用服务端包检查。 */
export async function directoryArchive(files: File[]): Promise<File> {
  if (!files.length || files.length > 50) throw new Error("请选择包含 1 至 50 个文件的 Skill 目录");
  const root = files[0].webkitRelativePath.split("/")[0];
  const paths = files.map(file => {
    const parts = file.webkitRelativePath.split("/");
    if (parts.shift() !== root || !parts.length || parts.some(part => !part || part === "." || part === ".." || part.includes("\\"))) throw new Error("目录路径无效，请重新选择 Skill 目录");
    return parts.join("/");
  });
  if (new Set(paths).size !== paths.length) throw new Error("目录包含重复文件路径");
  if (files.reduce((sum, file) => sum + file.size, 0) > 2 * 1024 * 1024) throw new Error("目录总大小不能超过 2MB");
  const parts: Uint8Array<ArrayBuffer>[] = [];
  const central: Uint8Array<ArrayBuffer>[] = [];
  let offset = 0;
  for (let i = 0; i < files.length; i++) {
    const name = new TextEncoder().encode(paths[i]);
    const data = new Uint8Array(await files[i].arrayBuffer());
    let crc = 0xffffffff;
    for (const byte of data) {
      crc ^= byte;
      for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
    }
    crc = (crc ^ 0xffffffff) >>> 0;
    const header = new Uint8Array(30 + name.length);
    const view = new DataView(header.buffer);
    view.setUint32(0, 0x04034b50, true); view.setUint16(4, 20, true);
    view.setUint16(6, 0x800, true); view.setUint16(12, 33, true);
    view.setUint32(14, crc, true); view.setUint32(18, data.length, true); view.setUint32(22, data.length, true);
    view.setUint16(26, name.length, true); header.set(name, 30);
    const record = new Uint8Array(46 + name.length);
    const cv = new DataView(record.buffer);
    cv.setUint32(0, 0x02014b50, true); cv.setUint16(4, 20, true); cv.setUint16(6, 20, true);
    record.set(header.subarray(6, 28), 8); cv.setUint32(42, offset, true); record.set(name, 46);
    parts.push(header, data); central.push(record); offset += header.length + data.length;
  }
  const end = new Uint8Array(22);
  const ev = new DataView(end.buffer);
  ev.setUint32(0, 0x06054b50, true); ev.setUint16(8, files.length, true); ev.setUint16(10, files.length, true);
  ev.setUint32(12, central.reduce((sum, entry) => sum + entry.length, 0), true); ev.setUint32(16, offset, true);
  const archive = new File([...parts, ...central, end], `${root}.zip`, { type: "application/zip" });
  if (archive.size > 2 * 1024 * 1024) throw new Error("目录打包后超过 2MB，请减少文件大小");
  return archive;
}
