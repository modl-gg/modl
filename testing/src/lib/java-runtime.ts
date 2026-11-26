export const PANEL_JAVA_VERSION = 21;

export function panelHostJava(serverJava: number): number {
  return Math.max(serverJava, PANEL_JAVA_VERSION);
}

export const JAVA_FEATURE_VERSION = /^\d{1,2}$/;

export function temurinJre(featureVersion: number): string {
  return `eclipse-temurin:${featureVersion}-jre`;
}

export function temurinJdk(featureVersion: number): string {
  return `eclipse-temurin:${featureVersion}-jdk`;
}
