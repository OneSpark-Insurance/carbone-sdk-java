package io.carbone;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import feign.FeignException;
import feign.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

class CarboneServices implements ICarboneServices {

    private static final Logger logger = LoggerFactory.getLogger(CarboneServices.class);

    private final ICarboneTemplateClient carboneTemplateClient;
    private final ICarboneRenderClient carboneRenderClient;
    private final ICarboneStatusClient carboneStatusClient;

    public CarboneServices(ICarboneTemplateClient carboneTemplateClient, ICarboneRenderClient carboneRenderClient, ICarboneStatusClient carboneStatusClient) {
        this.carboneTemplateClient = carboneTemplateClient;
        this.carboneRenderClient = carboneRenderClient;
        this.carboneStatusClient = carboneStatusClient;
    }

    @Override
    public String addTemplate(String templatePath) throws CarboneException, IOException {
        Path filePath = Paths.get(templatePath);
        byte[] fileBytes = Files.readAllBytes(filePath);
        return addTemplate(fileBytes);
    }

    @Override
    public String addTemplate(byte[] templateFile) throws CarboneException {
        CarboneResponse carboneResponse = carboneTemplateClient.addTemplate(templateFile);
        return carboneResponse.getData().getTemplateId();
    }

    @Override
    public boolean deleteTemplate(String templateId) throws CarboneException {
        CarboneResponse carboneResponse = carboneTemplateClient.deleteTemplate(templateId);
        return carboneResponse.isSuccess();
    }

    public boolean checkPathIsAbsolute(String path) {
        Path p = Paths.get(path);
        return p.isAbsolute();
    }

    public String generateTemplateId(String path) {
        try {
            File file = new File(path);
            byte[] fileBytes;
            try (FileInputStream fis = new FileInputStream(file)) {
                fileBytes = fis.readAllBytes();
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }

            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
                return null;
            }

            digest.update(fileBytes);
            byte[] hashByte = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashByte) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append(0);
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }


    public CarboneDocument render(String jsonData, String fileOrTemplateID) throws CarboneException {

        if (fileOrTemplateID == null || fileOrTemplateID.isEmpty()) {
            throw new CarboneException("Carbone SDK render error: argument is missing: file_or_template_id");
        }
        if (jsonData == null || jsonData.isEmpty()) {
            throw new CarboneException("Carbone SDK render error: argument is missing: json_data");
        }

        CarboneResponse renderResponse;
        File file = new File(fileOrTemplateID);

        try {
            if (!file.exists()) {
                renderResponse = carboneRenderClient.renderReport(jsonData, fileOrTemplateID);
            } else {
                String templateId = generateTemplateId(fileOrTemplateID);
                if (templateId == null) {
                    throw new CarboneException("Carbone SDK render error: failed to generate template ID");
                }

                try {
                    renderResponse = carboneRenderClient.renderReport(jsonData, templateId);
                } catch (CarboneException e) {
                    if (e.getHttpStatus() == 404) {
                        try {
                            Path filePath = Paths.get(fileOrTemplateID);
                            byte[] fileBytes = Files.readAllBytes(filePath);
                            CarboneResponse addTemplateResponse = carboneTemplateClient.addTemplate(fileBytes);

                            if (addTemplateResponse.isSuccess()) {
                                renderResponse = carboneRenderClient.renderReport(jsonData, addTemplateResponse.getData().getTemplateId());
                            } else {
                                throw new CarboneException("Carbone SDK render error: failed to add template: " +
                                    (addTemplateResponse.getError() != null ? addTemplateResponse.getError() : "unknown error"));
                            }
                        } catch (IOException ioErr) {
                            throw new CarboneException("Carbone SDK render error: failed to read template file: " + ioErr.getMessage());
                        }
                    } else if (e.getHttpStatus() == 401) {
                        throw new CarboneException("Carbone error: invalid token");
                    } else {
                        throw new CarboneException("Carbone SDK render error: " + e.getMessage());
                    }
                }
            }

            if (renderResponse == null) {
                throw new CarboneException("Carbone SDK render error: no response from render service");
            }

            if (!renderResponse.isSuccess()) {
                throw new CarboneException("Carbone SDK render error: " +
                    (renderResponse.getError() != null ? renderResponse.getError() : "render_id empty"));
            }

            if (renderResponse.getData() == null || renderResponse.getData().getRenderId() == null || renderResponse.getData().getRenderId().isEmpty()) {
                throw new CarboneException("Carbone SDK render error: render_id empty or invalid");
            }

            return getReport(renderResponse.getData().getRenderId());

        } catch (CarboneException e) {
            throw e;
        } catch (Exception e) {
            throw new CarboneException("Carbone SDK render error: unexpected error: " + e.getMessage());
        }
    }


    @Override
    public String renderReport(String renderData, String templateId) throws CarboneException {
        if (checkPathIsAbsolute(templateId)) {
            String newTemplateId = generateTemplateId(templateId);
            CarboneResponse carboneResponse = carboneRenderClient.renderReport(renderData, newTemplateId);
            return carboneResponse.getData().getRenderId();
        }

        CarboneResponse carboneResponse = carboneRenderClient.renderReport(renderData, templateId);
        return carboneResponse.getData().getRenderId();
    }

    @Override
    public CarboneDocument getReport(String renderId) throws CarboneException {
        return carboneRenderClient.getReport(renderId);
    }

    @Override
    public byte[] getTemplate(String templateId) throws CarboneException {
        CarboneFileResponse response = carboneTemplateClient.getTemplate(templateId);
        return response.getFileContent();
    }

    @Override
    public String getStatus() throws CarboneException {
        Response response = null;
        try {
            response = carboneStatusClient.getStatus();
            InputStream bodyIs = response.body().asInputStream();
            return new String(bodyIs.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CarboneException("Error reading response body");
        } catch (FeignException e) {
            throw new CarboneException("Carbone server error: " + e);
        } finally {
            if (response != null && response.body() != null) {
                try {
                    response.body().close();
                } catch (IOException e) {
                    logger.error("Failed to close response body", e);
                }
            }
        }
    }
}
