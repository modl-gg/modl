export const UPSTREAM_SOURCE_URL = 'https://github.com/modl-gg/modl';
export const WIKI_URL = `${UPSTREAM_SOURCE_URL}/wiki`;
export const WIKI_API_URL = `${UPSTREAM_SOURCE_URL}/wiki/API`;
export const WIKI_PANEL_PORT_URL = `${UPSTREAM_SOURCE_URL}/wiki/Panel-Port-and-Tunnels`;
export const WIKI_MIGRATING_URL = `${UPSTREAM_SOURCE_URL}/wiki/Migrating-from-Other-Plugins`;
const SOURCE_COMMIT = import.meta.env.VITE_MODL_SOURCE_COMMIT ?? '';
const BUILT_REPOSITORY = import.meta.env.VITE_MODL_SOURCE_REPOSITORY ?? '';
const SOURCE_REPOSITORY = BUILT_REPOSITORY === '' ? UPSTREAM_SOURCE_URL : BUILT_REPOSITORY;
export const RUNNING_SOURCE_URL = SOURCE_COMMIT === '' ? SOURCE_REPOSITORY : `${SOURCE_REPOSITORY}/tree/${SOURCE_COMMIT}`;
