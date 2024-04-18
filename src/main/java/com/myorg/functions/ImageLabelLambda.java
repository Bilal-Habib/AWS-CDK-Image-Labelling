package com.myorg.functions;

import static com.myorg.AwsCdkImageLabellingStack.LABELLED_BUCKET_NAME;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.rekognition.AmazonRekognition;
import com.amazonaws.services.rekognition.AmazonRekognitionClientBuilder;
import com.amazonaws.services.rekognition.model.BoundingBox;
import com.amazonaws.services.rekognition.model.DetectLabelsRequest;
import com.amazonaws.services.rekognition.model.DetectLabelsResult;
import com.amazonaws.services.rekognition.model.Image;
import com.amazonaws.services.rekognition.model.Instance;
import com.amazonaws.services.rekognition.model.Label;
import com.amazonaws.services.rekognition.model.S3Object;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.ImageIO;
import org.json.JSONObject;

public class ImageLabelLambda implements RequestHandler<SQSEvent, String> {

    private final AmazonRekognition rekognitionClient;
    private final AmazonS3 s3Client;

    public ImageLabelLambda() {
        rekognitionClient = AmazonRekognitionClientBuilder.defaultClient();
        s3Client = AmazonS3ClientBuilder.defaultClient();
    }

    @Override
    public String handleRequest(SQSEvent sqsEvent, Context context) {
        for (SQSEvent.SQSMessage message : sqsEvent.getRecords()) {
            String body = message.getBody();
            JSONObject bodyObject = new JSONObject(body);

            String bucketName = bodyObject.getJSONArray("Records").getJSONObject(0)
                .getJSONObject("s3").getJSONObject("bucket").getString("name");

            String key = bodyObject.getJSONArray("Records").getJSONObject(0)
                .getJSONObject("s3").getJSONObject("object").getString("key");

            // Decode the URL-encoded key name - not needed
            String decodedKeyName = URLDecoder.decode(key, StandardCharsets.UTF_8);

            // Call Rekognition to detect labels directly from S3 object
            DetectLabelsResult result = detectLabels(bucketName, decodedKeyName);

            // Overlay labels and bounding boxes on the image
            InputStream overlayedImage;
            try {
                overlayedImage = overlayLabels(bucketName, decodedKeyName, result.getLabels());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            // Upload the overlayed image to S3
            String labelledKey = "labeled_" + decodedKeyName;
            uploadToS3(labelledKey, overlayedImage);

            System.out.println("Labels detected and overlayed on the image. Result image uploaded to S3.");
        }
        return "Lambda Successful";
    }

    private DetectLabelsResult detectLabels(String bucketName, String key) {
        DetectLabelsRequest request = new DetectLabelsRequest()
            .withImage(new Image().withS3Object(new S3Object().withName(key).withBucket(bucketName)))
            .withMaxLabels(10).withMinConfidence(75F);
        return rekognitionClient.detectLabels(request);
    }

    private InputStream overlayLabels(String bucketName, String key, List<Label> labels) throws IOException {
        InputStream inputStream = s3Client.getObject(bucketName, key).getObjectContent();
        BufferedImage image = ImageIO.read(inputStream);

        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.setFont(new Font("Arial", Font.BOLD, 16));

        for (Label label : labels) {
            List<Instance> instances = label.getInstances();
            for (Instance instance : instances) {
                BoundingBox box = instance.getBoundingBox();
                int x = (int) (box.getLeft() * image.getWidth());
                int y = (int) (box.getTop() * image.getHeight());
                int width = (int) (box.getWidth() * image.getWidth());
                int height = (int) (box.getHeight() * image.getHeight());

                // Draw bounding box
                g.drawRect(x, y, width, height);

                // Draw label
                g.drawString(label.getName(), x, y - 5);
            }
        }

        g.dispose();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", outputStream);

        return new ByteArrayInputStream(outputStream.toByteArray());
    }

    private void uploadToS3(String key, InputStream inputStream) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            byte[] data = baos.toByteArray();

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType("image/jpeg");
            metadata.setContentLength(data.length);

            PutObjectRequest putRequest = new PutObjectRequest(LABELLED_BUCKET_NAME, key, new ByteArrayInputStream(data), metadata);
            s3Client.putObject(putRequest);
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }
}