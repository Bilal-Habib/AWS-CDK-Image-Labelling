package com.myorg;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.lambda.Architecture;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.eventsources.SqsEventSource;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.EventType;
import software.amazon.awscdk.services.s3.notifications.SqsDestination;
import software.amazon.awscdk.services.sqs.Queue;
import software.constructs.Construct;


public class AwsCdkImageLabellingStack extends Stack {

    private static final String ORIGINAL_BUCKET_NAME = "bucket-for-original-images";
    public static final String LABELLED_BUCKET_NAME = "bucket-for-labelled-images";
    private static final String QUEUE_NAME = "QueueForUnlabelledImages";
    private static final String LAMBDA_NAME = "LambdaForLabellingImages";

    public AwsCdkImageLabellingStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public AwsCdkImageLabellingStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        final var unlabelledImagesBucket = Bucket.Builder
            .create(this, ORIGINAL_BUCKET_NAME)
            .bucketName(ORIGINAL_BUCKET_NAME)
            .build();

        final var labelledImagesBucket = Bucket.Builder
            .create(this, LABELLED_BUCKET_NAME)
            .bucketName(LABELLED_BUCKET_NAME)
            .build();

        final var unlabelledImagesQueue = Queue.Builder
            .create(this, QUEUE_NAME)
            .queueName(QUEUE_NAME)
            .build();

        final var imageLabelLambda = Function.Builder
            .create(this, LAMBDA_NAME)
            .functionName(LAMBDA_NAME)
            .runtime(Runtime.JAVA_17)
            .architecture(Architecture.ARM_64)
            .timeout(Duration.seconds(20))
            .handler("com.myorg.functions.ImageLabelLambda::handleRequest")
            .code(Code.fromAsset("assets/ImageLabelLambda.jar"))
            .build();

        ApplyDestroyRemovalPolicyOnBuckets(unlabelledImagesBucket, labelledImagesBucket);

        GrantReadAccessToUnlabelledBucket(unlabelledImagesBucket, imageLabelLambda);
        GrantPutAccessToLabelledBucket(labelledImagesBucket, imageLabelLambda);
        GrantLambdaAccessToRekognition(imageLabelLambda);
        CreateSqsObjectCreatedNotification(unlabelledImagesQueue, imageLabelLambda, unlabelledImagesBucket);
    }

    private static void ApplyDestroyRemovalPolicyOnBuckets(Bucket unlabelledImagesBucket, Bucket labelledImagesBucket) {
        unlabelledImagesBucket.applyRemovalPolicy(RemovalPolicy.DESTROY);
        labelledImagesBucket.applyRemovalPolicy(RemovalPolicy.DESTROY);
    }

    private static void GrantPutAccessToLabelledBucket(Bucket labelledImagesBucket, Function imageLabelLambda) {
        labelledImagesBucket.grantPut(imageLabelLambda);
    }

    private static void GrantLambdaAccessToRekognition(Function imageLabelLambda) {
        imageLabelLambda.getRole().addManagedPolicy(ManagedPolicy.fromAwsManagedPolicyName("AmazonRekognitionFullAccess"));
    }

    private void CreateSqsObjectCreatedNotification(Queue unlabelledImagesQueue, Function imageLabelLambda,
                                                    Bucket unlabelledImagesBucket) {
        unlabelledImagesQueue.grantConsumeMessages(imageLabelLambda);
        imageLabelLambda.getRole().addManagedPolicy(ManagedPolicy.fromManagedPolicyArn(
            this, "lambdasqspolicy", "arn:aws:iam::aws:policy/service-role/AWSLambdaSQSQueueExecutionRole"));
        imageLabelLambda.addEventSource(new SqsEventSource(unlabelledImagesQueue));

        unlabelledImagesBucket.addEventNotification(
            EventType.OBJECT_CREATED,
            new SqsDestination(unlabelledImagesQueue));
    }

    private void GrantReadAccessToUnlabelledBucket(Bucket unlabelledImagesBucket, Function imageLabelLambda) {
        unlabelledImagesBucket.grantRead(imageLabelLambda);
    }
}
