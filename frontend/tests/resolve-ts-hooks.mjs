import path from "node:path";
import { pathToFileURL, fileURLToPath } from "node:url";

const srcRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../src");

export async function resolve(specifier, context, nextResolve) {
  if (specifier.startsWith("@/")) {
    const rel = specifier.slice(2);
    const withExt = rel.endsWith(".ts") || rel.endsWith(".tsx") ? rel : `${rel}.ts`;
    return nextResolve(pathToFileURL(path.join(srcRoot, withExt)).href, context);
  }

  try {
    return await nextResolve(specifier, context);
  } catch (error) {
    if (
      error?.code === "ERR_MODULE_NOT_FOUND"
      && (specifier.startsWith("./") || specifier.startsWith("../"))
      && !specifier.endsWith(".ts")
      && !specifier.endsWith(".tsx")
      && !specifier.endsWith(".js")
      && !specifier.endsWith(".mjs")
      && !specifier.endsWith(".json")
    ) {
      return nextResolve(`${specifier}.ts`, context);
    }
    throw error;
  }
}
