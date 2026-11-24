import * as React from "react"
import { useTranslation } from "react-i18next"
import { cva, type VariantProps } from "class-variance-authority"
import { AlertTriangle, AlertCircle, CheckCircle, Info, X } from "lucide-react"
import { cn } from "@/lib/utils"
import { isRenderable } from "@/components/ui/renderable"

const statusBannerVariants = cva(
  "relative flex items-start gap-2 rounded-md border px-3 py-2 text-foreground",
  {
    variants: {
      variant: {
        error: "border-destructive-text/20 bg-destructive-text/10",
        warning: "border-warning/20 bg-warning/10",
        success: "border-success/20 bg-success/10",
        info: "border-info/20 bg-info/10",
      },
    },
    defaultVariants: {
      variant: "info",
    },
  }
)

const iconVariants = cva("mt-0.5 shrink-0", {
  variants: {
    variant: {
      error: "text-destructive-text",
      warning: "text-warning",
      success: "text-success",
      info: "text-info",
    },
    size: {
      default: "h-4 w-4",
      sm: "h-4 w-4",
      lg: "h-5 w-5",
    },
  },
  defaultVariants: {
    variant: "info",
    size: "default",
  },
})

const defaultIcons = {
  error: AlertCircle,
  warning: AlertTriangle,
  success: CheckCircle,
  info: Info,
}

interface StatusBannerProps
  extends React.ComponentProps<"div">,
    VariantProps<typeof statusBannerVariants> {
  title?: string
  icon?: React.ReactNode
  iconSize?: "sm" | "default" | "lg"
  dismissible?: boolean
  onDismiss?: () => void
  action?: React.ReactNode
}

function StatusBanner({
  className,
  variant = "info",
  title,
  icon,
  iconSize = "default",
  dismissible,
  onDismiss,
  action,
  children,
  ...props
}: StatusBannerProps) {
  const { t } = useTranslation()
  const DefaultIcon = defaultIcons[variant || "info"]
  const IconComponent = icon ?? (
    <DefaultIcon className={cn(iconVariants({ variant, size: iconSize }))} />
  )

  return (
    <div
      role="alert"
      className={cn(statusBannerVariants({ variant }), className)}
      {...props}
    >
      {IconComponent}
      <div className="flex-1 min-w-0">
        {title && (
          <p className="text-sm font-medium">{title}</p>
        )}
        {isRenderable(children) && (
          <div className={cn("text-sm text-foreground/80", title && "mt-1")}>
            {children}
          </div>
        )}
        {isRenderable(action) && <div className="mt-3">{action}</div>}
      </div>
      {dismissible && onDismiss && (
        <button
          type="button"
          onClick={onDismiss}
          className="shrink-0 rounded-md p-1 opacity-70 hover:opacity-100 transition-opacity focus:outline-hidden focus:ring-2 focus:ring-offset-2 focus:ring-current"
          aria-label={t("ui.dismiss")}
        >
          <X className="h-4 w-4" />
        </button>
      )}
    </div>
  )
}

export { StatusBanner }
