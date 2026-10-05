package cn.hnust.selection.request;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/** Request a fixed, server-whitelisted batch CSV export. */
public class CreateAdminExportRequest {
    @NotBlank @Size(max = 24) private String exportType;
    @Size(max = 8) private String format = "CSV";
    public String getExportType() { return exportType; }
    public void setExportType(String exportType) { this.exportType = exportType; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
}
