import { join } from "node:path";
import { CACHE_DIR } from "../../lib/paths";
import { requirePinnedFile, type PinnedFile } from "../assets/pinned-file";

export const MUSIC_DIR = join(CACHE_DIR, "video-music");

export interface MusicTrack {
  title: string;
  author: string;
  licence: string;
  page: string;
  file: PinnedFile;
}

export interface MusicCredit {
  title: string;
  author: string;
  licence: string;
  source: string;
  file: string;
  sha256: string;
}

export const BACKGROUND_MUSIC: MusicTrack = {
  title: "Gymnopedie No. 1 (Erik Satie)",
  author: "Teknopazzo",
  licence: "CC0 1.0 (Wikimedia Commons)",
  page: "https://commons.wikimedia.org/wiki/File:Gymnopedie_No._1..ogg",
  file: {
    url: "https://upload.wikimedia.org/wikipedia/commons/b/b7/Gymnopedie_No._1..ogg",
    sha256: "13e4e03797169392166b9f11d9bf9c421c022b48ad87d9b10bd441ce068502da",
    path: join(MUSIC_DIR, "gymnopedie-1.ogg"),
  },
};

const MINECRAFT_ASSET_INDEX = "https://piston-meta.mojang.com/v1/packages/afea8bb2aeb664d53f219ae248248a264afa27c8/34.json";
const MINECRAFT_SOUNDTRACK_LICENCE = "Minecraft soundtrack (Mojang), used with the acceptance of the modl owner";

function minecraftAsset(asset: string, sha1: string, sha256: string): PinnedFile {
  return {
    url: `https://resources.download.minecraft.net/${sha1.slice(0, 2)}/${sha1}`,
    sha256,
    path: join(MUSIC_DIR, "minecraft", asset.split("/").at(-1) ?? sha1),
  };
}

export const SWEDEN: MusicTrack = {
  title: "Sweden",
  author: "C418",
  licence: MINECRAFT_SOUNDTRACK_LICENCE,
  page: `${MINECRAFT_ASSET_INDEX} (Minecraft 26.3 asset index 34, minecraft/sounds/music/game/sweden.ogg)`,
  file: minecraftAsset("minecraft/sounds/music/game/sweden.ogg", "be9b14926936c4509f7fe5002cdfbba3b2752d82", "f5169a831c71ecf5079cc74ba0283b95c068c3324b976e39037faecc7f51cb04"),
};

export const PIGSTEP: MusicTrack = {
  title: "Pigstep",
  author: "Lena Raine",
  licence: MINECRAFT_SOUNDTRACK_LICENCE,
  page: `${MINECRAFT_ASSET_INDEX} (Minecraft 26.3 asset index 34, minecraft/sounds/records/pigstep.ogg)`,
  file: minecraftAsset("minecraft/sounds/records/pigstep.ogg", "12174a8a8332a4156d57d57f370932ad28789df0", "dcb9467b78c3d635f849306e2068ae7d1b3e22c057baa683b86373da3ffc56a3"),
};

export function musicCredit(track: MusicTrack): MusicCredit {
  return { title: track.title, author: track.author, licence: track.licence, source: track.page, file: track.file.url, sha256: track.file.sha256 };
}

export async function requireMusic(track: MusicTrack): Promise<string> {
  return requirePinnedFile(track.file);
}
