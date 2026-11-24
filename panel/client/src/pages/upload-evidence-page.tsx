import { useState, useRef } from "react";
import { useTranslation } from 'react-i18next';
import { useParams } from "wouter";
import { Loader2, X, CheckCircle, AlertCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  uploadEvidenceFile,
  useEvidenceUploadToken,
  useSubmitEvidence,
  type EvidenceSubmission,
} from "@/hooks/data/evidence-upload";
import { describeError } from "@/lib/http-error";

type EvidenceUploadState =
  | { status: "pending" }
  | { status: "uploading"; progress: number }
  | { status: "uploaded"; url: string; key: string }
  | { status: "error"; error: string };

interface EvidenceFileRow {
  id: string;
  file: File;
  upload: EvidenceUploadState;
}

function makeFileId(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `file-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function toSubmission(row: EvidenceFileRow): EvidenceSubmission[] {
  return row.upload.status === "uploaded"
    ? [{ url: row.upload.url, fileName: row.file.name, fileType: row.file.type, fileSize: row.file.size }]
    : [];
}

function uploadStatusSuffix(upload: EvidenceUploadState, uploadedLabel: string): string {
  switch (upload.status) {
    case "pending":
      return "";
    case "uploading":
      return ` - ${upload.progress}%`;
    case "uploaded":
      return ` - ${uploadedLabel}`;
    case "error":
      return ` - ${upload.error}`;
  }
}

export default function UploadEvidencePage() {
  const params = useParams<{ token: string }>();
  const token = params.token;
  const { t } = useTranslation();

  const tokenQuery = useEvidenceUploadToken(token);
  const submitMutation = useSubmitEvidence(token);
  const [error, setError] = useState<string | null>(null);
  const [files, setFiles] = useState<EvidenceFileRow[]>([]);
  const [isDragging, setIsDragging] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const submitted = submitMutation.isSuccess;
  const submitting = submitMutation.isPending;

  const updateUpload = (fileId: string, upload: EvidenceUploadState) => {
    setFiles((prev) => prev.map((f) => (f.id === fileId ? { ...f, upload } : f)));
  };

  const uploadRow = async (row: EvidenceFileRow) => {
    updateUpload(row.id, { status: "uploading", progress: 0 });
    try {
      const uploaded = await uploadEvidenceFile(token, row.file, (progress) => {
        updateUpload(row.id, { status: "uploading", progress: progress.percentage });
      });
      updateUpload(row.id, { status: "uploaded", ...uploaded });
    } catch (err) {
      updateUpload(row.id, { status: "error", error: describeError(err, t('evidence.uploadFailed')) });
    }
  };

  const uploadRowsSequentially = async (rows: EvidenceFileRow[]) => {
    for (const row of rows) {
      await uploadRow(row);
    }
  };

  const handleFiles = (newFiles: File[]) => {
    const maxUploadBytes = tokenQuery.data?.maxUploadBytes;
    if (token === "" || submitted || maxUploadBytes === undefined) return;

    const oversizedFiles = newFiles.filter((f) => f.size > maxUploadBytes);
    if (oversizedFiles.length > 0) {
      setError(t('evidence.filesTooLarge', { names: oversizedFiles.map((f) => f.name).join(", ") }));
      return;
    }

    const rows: EvidenceFileRow[] = newFiles.map((file) => ({
      id: makeFileId(),
      file,
      upload: { status: "pending" },
    }));

    setFiles((prev) => [...prev, ...rows]);
    void uploadRowsSequentially(rows);
  };

  const removeFile = (id: string) => {
    setFiles((prev) => prev.filter((f) => f.id !== id));
  };

  const uploadedEvidence = files.flatMap(toSubmission);
  const uploadedCount = uploadedEvidence.length;
  const hasErrors = files.some((f) => f.upload.status === "error");

  const handleSubmit = () => {
    if (token === "" || submitting || submitted || uploadedCount === 0) return;
    submitMutation.mutate(uploadedEvidence, {
      onError: (err) => {
        setError(describeError(err, t('evidence.submitFailed')));
      },
    });
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
    if (submitted) return;
    const droppedFiles = Array.from(e.dataTransfer.files);
    if (droppedFiles.length > 0) {
      handleFiles(droppedFiles);
    }
  };

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(true);
  };

  const handleDragLeave = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
  };

  if (tokenQuery.isPending) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <Loader2 className="h-8 w-8 animate-spin text-primary" />
      </div>
    );
  }

  if (tokenQuery.isError) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="text-center max-w-md p-8">
          <h1 className="text-xl font-semibold mb-2">{t('evidence.uploadUnavailable')}</h1>
          <p className="text-sm text-muted-foreground">{describeError(tokenQuery.error, t('evidence.validateFailed'))}</p>
        </div>
      </div>
    );
  }

  const tokenInfo = tokenQuery.data;

  if (submitted) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="text-center max-w-md p-8">
          <h1 className="text-xl font-semibold mb-2">{t('evidence.evidenceUploaded')}</h1>
          <p className="text-sm text-muted-foreground">
            {t('evidence.evidenceUploadedDesc', { count: submitMutation.variables.length, id: tokenInfo.punishmentId })}
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen">
      <div className="max-w-2xl mx-auto p-6 pt-12">
        <div className="mb-6 space-y-3">
          <h1 data-modl="page-title" className="text-xl font-semibold">{t('evidence.uploadEvidence')}</h1>
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
            <dt className="text-muted-foreground">{t('evidence.punishment')}</dt>
            <dd className="font-mono">{tokenInfo.punishmentId}</dd>
            <dt className="text-muted-foreground">{t('evidence.player')}</dt>
            <dd>{tokenInfo.playerName}</dd>
            <dt className="text-muted-foreground">{t('evidence.uploadedBy')}</dt>
            <dd>{tokenInfo.issuerName}</dd>
          </dl>
        </div>

        <div
          data-modl="evidence-dropzone"
          className={`border-2 border-dashed rounded-lg p-8 text-center transition-colors cursor-pointer ${
            isDragging
              ? "border-primary bg-primary/5"
              : "border-muted-foreground/25 hover:border-muted-foreground/50"
          }`}
          onDrop={handleDrop}
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onClick={() => fileInputRef.current?.click()}
        >
          <p className="text-sm text-muted-foreground mb-1">
            {t('evidence.dragAndDrop')}
          </p>
          <p className="text-xs text-muted-foreground">
            {t('evidence.fileTypes')}
          </p>
          <input
            ref={fileInputRef}
            type="file"
            multiple
            className="hidden"
            onChange={(e) => {
              if (e.target.files) {
                handleFiles(Array.from(e.target.files));
                e.target.value = "";
              }
            }}
          />
        </div>

        {files.length > 0 && (
          <div data-modl="evidence-files" className="mt-6 space-y-3">
            {files.map((f) => (
              <div
                key={f.id}
                className="flex items-center gap-3 p-3 rounded-lg border bg-card"
              >
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium truncate">{f.file.name}</p>
                  <p className="text-xs text-muted-foreground">
                    {(f.file.size / 1024 / 1024).toFixed(2)} MB
                    {uploadStatusSuffix(f.upload, t('evidence.uploaded'))}
                  </p>
                  {f.upload.status === "uploading" && (
                    <div className="mt-1 h-1 bg-muted rounded-full overflow-hidden">
                      <div
                        className="h-full bg-primary transition-all duration-300"
                        style={{ width: `${f.upload.progress}%` }}
                      />
                    </div>
                  )}
                </div>
                <div className="shrink-0">
                  {f.upload.status === "uploading" && (
                    <Loader2 className="h-4 w-4 animate-spin text-primary" />
                  )}
                  {f.upload.status === "uploaded" && (
                    <CheckCircle className="h-4 w-4 text-green-500" />
                  )}
                  {f.upload.status === "error" && (
                    <AlertCircle className="h-4 w-4 text-destructive" />
                  )}
                  {(f.upload.status === "uploaded" || f.upload.status === "error") && (
                    <button
                      type="button"
                      onClick={() => removeFile(f.id)}
                      className="ml-2 p-1 hover:bg-muted rounded-md"
                      aria-label={t('ticket.removeFile', { name: f.file.name })}
                    >
                      <X className="h-3 w-3" />
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}

        {error !== null && (
          <div className="mt-4 p-3 rounded-lg bg-destructive/10 text-destructive text-sm">
            {error}
          </div>
        )}

        {uploadedCount > 0 && (
          <div className="mt-6">
            <Button data-modl="submit-button" onClick={handleSubmit} disabled={submitting} className="w-full">
              {submitting && <Loader2 className="h-4 w-4 animate-spin" />}
              {submitting ? t('evidence.savingEvidence') : t('evidence.saveEvidence', { count: uploadedCount })}
            </Button>
            {hasErrors && (
              <p className="text-xs text-muted-foreground mt-2 text-center">
                {t('evidence.filesWithErrorsExcluded')}
              </p>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
