/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Absolute origin of the backend API. Leave empty when same origin. */
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
