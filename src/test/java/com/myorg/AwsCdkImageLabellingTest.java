 package com.myorg;

 import java.util.Map;
 import org.junit.Test;
 import software.amazon.awscdk.App;
 import software.amazon.awscdk.assertions.Template;
 import software.amazon.awscdk.services.lambda.Runtime;

 public class AwsCdkImageLabellingTest {

     @Test
     public void testStack() {
         App app = new App();
         AwsCdkImageLabellingStack stack = new AwsCdkImageLabellingStack(app, "test");

         Template template = Template.fromStack(stack);

         template.hasResourceProperties("AWS::S3::Bucket", Map.of(
             "BucketName", "bucket-for-original-images"
         ));

         template.hasResourceProperties("AWS::S3::Bucket", Map.of(
             "BucketName", "bucket-for-labelled-images"
         ));

         template.hasResourceProperties("AWS::SQS::Queue", Map.of(
             "QueueName", "QueueForOriginalImages"
         ));

         template.hasResourceProperties("AWS::Lambda::Function", Map.of(
             "FunctionName", "LambdaForLabellingImages",
             "Handler", "com.myorg.functions.ImageLabelLambda::handleRequest",
             "Runtime", Runtime.JAVA_17.getName()
         ));
     }
 }
