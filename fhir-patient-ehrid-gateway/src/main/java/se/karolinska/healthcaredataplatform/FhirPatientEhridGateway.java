package se.karolinska.healthcaredataplatform;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.apache.camel.Predicate;
import org.apache.camel.builder.AggregationStrategies;
import org.apache.camel.builder.PredicateBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.http.HttpComponent;
import org.apache.camel.component.jslt.JsltConstants;
import org.apache.camel.http.common.HttpMethods;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.http.HttpConnection;
import org.apache.http.client.methods.HttpPost;

import com.jayway.jsonpath.JsonPath;

import net.minidev.json.JSONArray;

public class FhirPatientEhridGateway extends RouteBuilder {

    @Override
    public void configure() throws Exception {

        // Predicates for steering different route paths
        Predicate isConditional = PredicateBuilder.and(
            header("If-None-Exist").regex("^identifier=.+\\|.+$"),
            method(Utils.class, "doIdentifiersMatch(${headers['If-None-Exist']}, ${body})")
        );
        Predicate isPatientCreate = PredicateBuilder.and(
            header(Exchange.HTTP_METHOD).isEqualTo(HttpMethods.POST),
            header(Exchange.HTTP_PATH).isEqualToIgnoreCase("Patient")
        );
        Predicate isPatientUpdate = PredicateBuilder.and(
            header(Exchange.HTTP_METHOD).isEqualTo(HttpMethods.PUT),
            header(Exchange.HTTP_PATH).regex("^Patient\\/[A-z0-9\\-].+$")
        );
        Predicate ehrIdentifierExists = PredicateBuilder.and(
            method(Utils.class, "doesIdentifierExist(${body}, {{ehr.system}})")
        );

        // Primary gateway listener (note with compression=true so our route requests/responses can handle both cases)
        from("netty-http:http://0.0.0.0:{{gateway.http.port}}/?matchOnUriPrefix=true&compression=true")
            .log("INCOMING HEADERS=\n${headers}")
            .log("INCOMING BODY=\n${body}")

            // Transform request in various ways as needed
            .choice()
                .when(isPatientCreate)
                    .log("isPatientCreate")
                    .choice()
                        .when(isConditional).to("direct:getOrCreatePatient")
                        .otherwise().to("direct:writeWithoutConditional").stop() // add failure response and stop Exchange
                    .endChoice()
                .when(isPatientUpdate)
                    .log("isPatientUpdate")
                    .choice()
                        .when(isConditional).to("direct:getOrUpdatePatient")
                        .otherwise().to("direct:writeWithoutConditional").stop() // add failure response and stop Exchange
                    .endChoice()
                .otherwise()
                    .log("is otherwise")
            .end()

            // Send the request to our target FHIR service
            .to("direct:toFhir");

        // Set failure header and message when trying to write without conditional (missing 'If-None-Exist')
        from("direct:writeWithoutConditional")
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(HttpURLConnection.HTTP_BAD_REQUEST))
            .setBody(constant("{\"error\": \"This service does not allow writes without specifying a conditional using the 'If-None-Exist' header with a single resource identifier indicated by using the format 'identifier=system|value', and that this same identifier must exist in the request body. Refer to [https://build.fhir.org/http.html#Http-Headers] for more information.\"}")) // TODO a good/better message here?
            ;
        
        // Primary gateway target producer
        from("direct:toFhir")
            // TODO: on exception should we check if ehrId was created before this fhir exception, and then delete/mark it as deleted in EHR ?? "Saga pattern"

            // If the call to the FHIR service fails then Camel will return a NettyHttpOperationFailedException stacktrace to the client
            // Instead of this we would rather just return the failure that comes from the FHIR service itself back to the user
            //  (so allow it to fail, and let the failure's body and headers be what is sent back to the client)
            .log("Sending a new request to FHIR...")
            .log("HEADERS=${headers}")
            .log("BODY=${body}")
            .removeHeader("Accept-Encoding")
            .removeHeader(Exchange.HTTP_URI)
            .removeHeader(Exchange.HTTP_URL)
            //.doTry()
                // we will use the vanilla "HTTP" component for this instead of using Netty HTTP's client, due to some caching issues with the Netty HTTP Producer
                .toD("{{fhir.http.scheme}}:{{fhir.http.hostname}}{{fhir.http.basepath}}/${headers." + Exchange.HTTP_PATH + "}?connectionClose=true") // -1 cacheSize to turn off
                //.toD("netty-http:{{fhir.http.scheme}}://{{fhir.http.hostname}}{{fhir.http.basepath}}/${headers." + Exchange.HTTP_PATH + "}?compression=false&disconnect=true") // -1 cacheSize to turn off
                .setBody(simple("${body}", String.class)) // read non-cached stream into body once
                .log("Response from FHIR=${body}")
            //.doCatch(Exception.class) // don't bomb the Camel Route if an exception happened, just let the exception from FHIR return back to the caller
            //    .log("Exception from FHIR=${body}")
            .end();




/***
 * getOrCreatePatient (POST) ->
 *   search Patient (1)
 *     if found:
 *       check for EHR identifier (2)
 *          if found:
 *             TODO: should check if this is a valid EHR with /ehr/{ehr_id} ?
 *             return (? "stop" basically)
 *          else:
 *             fetch and add EHR identfier (3)
 *             change method to PUT (4)
 *             send to FHIR (5)
 *     else:
 *       fetch and add EHR identifier (3)
 *       add PatientID as UUID to body and path (6)
 *       change method to PUT (4)
 *       send to FHIR (5)
 *
 * getOrUpdatePatient (PUT) ->
 *   check for EHR identifier (2)
 *     if found:
 *       TODO: should check if this is a valid EHR with /ehr/{ehr_id} ?
 *       send to FHIR (5)
 *     else:
 *       fetch and add EHR identfier (3)
 *       send to FHIR (5)
 * 
 * (1) searchPatient
 * (2) Predicate doesEhrIdentifierExist
 * (3) createAndAddEhrId -> createEhrId
 * (4) simple so just 
 * (5) toFhir
 * (6) addNewPatientUUID
 */


        // Patient GetOrUpdate (PUT with If-None-Exist)
        from("direct:getOrUpdatePatient")
            .choice()
                .when(ehrIdentifierExists) // "all good", do nothing.. TODO should we verify this EHR actual exists in the EHR system, and if not, what do we do?
                .otherwise()
                    .to("direct:createAndAddEhrId") // add missing EHR before allowing send of PUT
            .end();

        // Patient GetOrCreate (POST with If-None-Exist)
        from("direct:getOrCreatePatient")

            .enrich("direct:searchPatient",
                new AggregationStrategy() {
                    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
                        if (newExchange == null)
                            return oldExchange;
                        String response = newExchange.getIn().getBody(String.class);
                        if (response != null && !response.isBlank())
                            oldExchange.setProperty("foundPatient", response);
                        return oldExchange;
                    }
                }
            )

            .log("Result of property=${exchangeProperty.foundPatient}")

            .choice()
                .when(exchangeProperty("foundPatient").isNotNull())
                    .setBody(exchangeProperty("foundPatient"))
                    .choice()
                        .when(ehrIdentifierExists) // TODO should we verify this EHR actual exists in the EHR system, and if not, what do we do?
                        .stop() // "found" a patient that already seems to have an EHR ID for this EHR system , just return what we found? otherwise go ahead and send to FHIR as POST and let it return ? (then it is a double-call towards FHIR, but maybe we don't care?)
                    .endChoice()
                .otherwise()
                    // This patient was not found in the FHIR service, so create and set up a new Patient UUID for the request towards FHIR
                    .to("direct:addNewPatientUUID")
                .end()
            .end()
            .to("direct:createAndAddEhrId");

        // Search if Patient already exists using the FHIR Search API
        from("direct:searchPatient")
            .setBody(header("If-None-Exist"))
            .removeHeaders("*")
            .setHeader(Exchange.HTTP_METHOD, constant(HttpMethods.POST))
            .setHeader(Exchange.HTTP_PATH, constant("Patient/_search"))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/x-www-form-urlencoded"))
            .setHeader("ts", method(System.class, "currentTimeMillis")) // set current timestamp as a header to make each search request unique (hack to avoid cache?)
                // TODO: Bug here .. if the last request from this netty-http client was this exact same URL then the result is cached
                // This means if someone calls getOrCreate over and over on the same Patient and there is no other activity happening, then
                //  this logic will keep getting the cached result (search returned 0 responses) so will think a new EHR needs to be created.
                //  Then we will create and inject a new EHR to this request, but the Patient already exists in FHIR so it will just return back
                //  the one it already had. In the end we would end up with a lot of extra orphan EHR IDs from this bug
                // Tried to fix by using disableStreamCache=true but Camel just hangs and it does not work....
                //.setHeader("Cache-Control", constant("no-store, no-cache, max-age=0, s-maxage=0"))
                .log("Patient Search Body=\n${body}")
                .log("Patient Search Headers=\n${headers}")
                .to("direct:toFhir")
                .log("Patient Search Response=\n${body}")
                .choice()
                    .when(jsonpath("$.total").isGreaterThanOrEqualTo(1))
                        // TODO: Should this check for EHR System that matches our EHR's system ID, create a new EHR in our EHR if it is missing, and then update the FHIR resource?
                        // Right now it just assumes if the Patient with the same personnummer exists, just return it instead of creating a new one
                        // use JSLT here since JSONPath is only returning as an Object string representation instead of JSON and could not get it to work with jsonpathWriteAsString
                        .setHeader(JsltConstants.HEADER_JSLT_STRING, constant(".entry[0].resource"))
                        .to("jslt:dummy?allowTemplateFromHeader=true")
                        .log("Found Patient=\n${body}")
                    .otherwise()
                        // "total" was not >= 1 so throw exception so the enrich does not take this body
                        .setBody(constant(""))
                        //.throwException(new IllegalArgumentException("No existing patient found."))
                    .end()
            .end();

        from("direct:addNewPatientUUID")
            // For new Patients we want to use a UUID for the ID instead of FHIR's default (integer)
            // Per the FHIR spec this means we need to explicitly state the UUID both in the URL path and the data itself,
            //  and change the method from POST to PUT
            .setProperty("PatientId", method(UUID.class, "randomUUID"))
            .to("jslt:transform-Patient-injectPatientId.json?allowContextMapAll=true")
            .setHeader(Exchange.HTTP_PATH, simple("${headers." + Exchange.HTTP_PATH + "}/${exchangeProperty.PatientId}"))
            .setHeader(Exchange.HTTP_METHOD, constant(HttpMethods.PUT));

        // Fetch a new EHR ID and inject it into the existing payload using a JSON Transformation
        from("direct:createAndAddEhrId")

            .enrich("direct:createEhrId", new AggregationStrategy() {
                public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
                    if (newExchange == null)
                        return oldExchange;
                    String response = newExchange.getIn().getBody(String.class);
                    if (response != null && !response.isBlank()) {
                        //TODO add some checks if the FHIR resource is active, is use: official, has right SNOMED code, etc ?
                        String ehrId = Utils.getJsonPathValue(response, "$.ehr_id.value");
                        String ehrStatusUid = Utils.getJsonPathValue(response, "$.ehr_status.uid.value");
                        String ehrSystemId = Utils.extractPattern(ehrStatusUid, "::(.*?)::");
                        if (!ehrId.isBlank() && !ehrStatusUid.isBlank() && !ehrSystemId.isBlank()) {
                            oldExchange.setProperty("ehrId", ehrId);
                            oldExchange.setProperty("ehrStatusUid", ehrStatusUid);
                            oldExchange.setProperty("ehrSystemId", ehrSystemId);
                        }
                    }
                    return oldExchange;
                }
            })

            .log("Created new Patient EHR with ehrId=${exchangeProperty.ehrId}")

            .choice() // make sure there is an EHR and System
                .when(
                    PredicateBuilder.or(
                        exchangeProperty("ehrId").isNull(),
                        exchangeProperty("ehrId").isEqualTo(""),
                        exchangeProperty("ehrSystemId").isNull(),
                        exchangeProperty("ehrSystemId").isEqualTo("")
                    )
                )
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(HttpURLConnection.HTTP_INTERNAL_ERROR))
                    .throwException(new IllegalArgumentException("Missing EHR ID or System from response."))
                .end()
            .end()

            .to("jslt:transform-Patient-injectIdentifier.json?allowContextMapAll=true");

        // Create and return a new EHR ID in the EHR system
        from("direct:createEhrId")
            // TODO: maybe it is good to create with a subject and the subject can be the FHIR Resource ID ?
            .setBody(constant(""))
            .removeHeaders("*")
            .setHeader(Exchange.HTTP_METHOD, constant(HttpMethods.POST))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .setHeader("Accept", constant("application/json"))
            .setHeader("Prefer", constant("return=representation"))
            .toD("{{ehr.http.scheme}}:{{ehr.http.hostname}}{{ehr.http.basepath}}/ehr")
            .log("Response from EHR Create=\n${body}");

    }

    protected final static class Utils {

        static String extractPattern(String source, String matchPattern) {
            Pattern pattern = Pattern.compile(matchPattern);
            Matcher matcher = pattern.matcher(source);
            if (matcher.find())
                return matcher.group(1);
            else
                return "";
        }

        static boolean doesIdentifierExist(String body, String identifierSystem) {
            System.out.println("doesIdentifierExist('" + body + "', '" + identifierSystem + "')");
            try {
                JSONArray json = JsonPath.read(body, "$.identifier[?(@.system == '" + identifierSystem + "')].value");
                return !json.isEmpty() && !json.get(0).toString().isBlank();
            }
            catch (Exception e) {
                // if anything fails at any time, then it is "not a match"; return false
                return false;
            }
        }

        static boolean doIdentifiersMatch(String ifNoneExistHeader, String body) {
            System.out.println("doIdentifiersMatch('" + ifNoneExistHeader + "', '" + body + "')");
            try {
                String headerIdentifierSystem = URLDecoder.decode(
                    extractPattern(ifNoneExistHeader, "^identifier=(.+?)\\|.+$"),
                    StandardCharsets.UTF_8
                );
                String headerIdentifierValue = URLDecoder.decode(
                    extractPattern(ifNoneExistHeader, "^identifier=.+\\|(.+?)$"),
                    StandardCharsets.UTF_8
                );

                String bodyIdentifierValue = getJsonPathValue(body, "$.identifier[?(@.system == '" + headerIdentifierSystem + "')].value");
                // Has to have a value and the two have to match
                return bodyIdentifierValue != null && !bodyIdentifierValue.isBlank() && bodyIdentifierValue.equals(headerIdentifierValue);
            }
            catch (Exception e) {
                // if anything fails at any time, then it is "not a match"; return false
                return false;
            }
        }

        static String getJsonPathValue(String jsonBody, String jsonPath) {
            Object result = JsonPath.read(jsonBody, jsonPath);
            if (result instanceof JSONArray) {
                JSONArray json = (JSONArray) result;
                if (!json.isEmpty())
                    return json.get(0).toString();
                else
                    return ""; //TODO throw exception or allow empty/null?
            } else if(result instanceof String) {
                return (String) result;
            } else {
                return ""; //TODO throw exception or allow empty/null?
            }
        }

    }

}

/*
 * mvn clean compile quarkus:dev
 * curl "http://localhost:8989/v1/whatever/i/want?some-stuf=here&cool=too"
 * curl -X POST --header "Content-Type: application/json" --data '{}' "http://localhost:8989/v1/whatever/i/want"
 * 
 * jq '(.identifier[] | select(.system == "ehrid")) |= . + {system: "ehrid", value: "ab65de553dccc"}'
 * 
 * curl -X POST --header "Content-Type: application/json" --header "if-none-exist: identifier=http://electronichealth.se/identifier/personnummer|202306151234" --data @patient.json "http://localhost:8989/Patient"
 */
