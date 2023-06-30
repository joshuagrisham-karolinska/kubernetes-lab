# Kubernetes Lab Environment

```sh

# Drop and re-create Kind cluster
kind delete cluster --name kubernetes-lab
kind create cluster --name kubernetes-lab --config kind-config.yaml

# Start local Kind image registry (see https://kind.sigs.k8s.io/docs/user/local-registry/)
./start-kind-registry.sh

# Install Kubernetes Dashboard
kubectl apply -f https://raw.githubusercontent.com/kubernetes/dashboard/v2.7.0/aio/deploy/recommended.yaml
kubectl apply -f resources/dashboard.yaml --namespace kubernetes-dashboard

# Install Cert-Manager
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.11.1/cert-manager.yaml
# wait and then create certs:
kubectl rollout status --namespace cert-manager deployment/cert-manager-webhook --timeout=300s
kubectl apply -f resources/cert-manager-ca-issuer.yaml --namespace cert-manager
# check certs:
sleep 20
kubectl get secrets -n cert-manager selfsigned-cert-test-tls -o yaml

# Install Crunchy Operator (PGO) helm chart (add --dry-run to test)
#rm -rf postgres-operator-examples
#git clone https://github.com/CrunchyData/postgres-operator-examples.git postgres-operator-examples

helm install --create-namespace --namespace crunchy-operator pgo postgres-operator-examples/helm/install/

# remove crunch if desired
#helm delete --namespace crunchy-operator pgo

# Install VDP helm chart (add --dry-run to test)
helm install --create-namespace --namespace healthcare-data-platform healthcare-data-platform healthcare-data-platform/

# forward ports for EHRBase and HAPI if/when desired
kubectl port-forward --namespace healthcare-data-platform service/ehrbase 8080:8080
kubectl port-forward --namespace healthcare-data-platform service/hapi-fhir 8081:8080

# import organizations if/when desired
./fhir-patient-ehrid-gateway/import-organizations.sh \
  "http://localhost:8081/fhir" \
  fhir-patient-ehrid-gateway/import-organizations.ndjson

# remove VDP if desired
#helm delete --namespace healthcare-data-platform healthcare-data-platform

# Create a token to login to kubernetes-dashboard
kubectl -n kubernetes-dashboard create token admin-user
kubectl proxy
# Dashboard URL is: http://localhost:8001/api/v1/namespaces/kubernetes-dashboard/services/https:kubernetes-dashboard:/proxy/

# Install OPA with kube-mgmt (https://artifacthub.io/packages/helm/opa-kube-mgmt/opa-kube-mgmt)
## Add OPAs Helm Repo
helm repo add opa https://open-policy-agent.github.io/kube-mgmt/charts
helm repo update
## Install opa-kube-mgmt without authorization
helm upgrade -i -n opa --create-namespace opa opa/opa-kube-mgmt --set authz.enabled=false --set mgmt.namespaces=* --set replicas=3
# Add example OPA Policy
kubectl apply -f resources/opa-policies.yaml

kubectl port-forward --namespace opa service/opa-opa-kube-mgmt 18181:8181
curl --insecure -X POST --header 'Content-Type: application/json' --data '{"input":{"message":"world"}}' https://localhost:18181/v1/data/play/hello

curl --insecure -X POST --header 'Content-Type: application/json' --data '{"input":{"message":"world"}}' https://localhost:18181/v1/data/api/%7B%7B%20.Values.name%20%7D%7D/%7B%7B%20.Values.environment%20%7D%7D/openehr/ehr/goodbye

# Install Couchbase
# First download and unpack from here: https://www.couchbase.com/downloads?family=open-source-kubernetes
cd couchbase-autonomous-operator_2.4.1-130-kubernetes-linux-amd64
kubectl create -f crd.yaml
kubectl create namespace couchbase-operator
bin/cao create admission --namespace couchbase-operator --scope cluster
bin/cao create operator --namespace couchbase-operator --scope cluster
cd ..

# will fail first time (needs secret from cert), so run 2x
kubectl apply -f resources/cb1.yaml
sleep 100
kubectl apply -f resources/cb1.yaml

kubectl port-forward service/cb1 --namespace cb1 18091:8091
# URL to Couchbase Dashboard: https://localhost:18091/

kubectl apply -f resources/cb1-buckets.yaml
kubectl apply -f resources/cb1-users.yaml

cd certificates/cb1user
curl --cacert ca.crt --cert tls.crt --key tls.key https://localhost:18091/pools/default/buckets -vv
cd ../..


# will fail first time (needs secret from cert), so run 2x
kubectl apply -f resources/cb2.yaml
sleep 100
kubectl apply -f resources/cb2.yaml

kubectl -n kubernetes-dashboard create token admin-user
kubectl port-forward service/cb2 --namespace cb1 28091:18091
https://localhost:28091/

kubectl apply -f resources/cb2-buckets.yaml
kubectl apply -f resources/cb2-users.yaml

```

## Old instructions TODO Cleanup?

Create a root CA key (see: <https://medium.com/@charled.breteche/manage-ssl-certificates-for-local-kubernetes-clusters-with-cert-manager-9037ba39c799>)

```sh
mkdir -p certificates
openssl genrsa -out certificates/root-ca-key.pem 2048
openssl req -x509 -new -nodes -key certificates/root-ca-key.pem -days 3650 -sha256 -out certificates/root-ca.pem -subj "/CN=kube-ca"
```

Now start the kind cluster (assuming certs created per above)

```sh
kind create cluster --name keycloak-test --config kind-config.yaml
```

Delete (later)

```sh
kind delete cluster --name keycloak-test
```

Ensure your kubectl is now pointing to your local kind env:

```sh
kubectl cluster-info
```

Install Dashboard (<https://kubernetes.io/docs/tasks/access-application-cluster/web-ui-dashboard/>)

```sh
kubectl apply -f https://raw.githubusercontent.com/kubernetes/dashboard/v2.7.0/aio/deploy/recommended.yaml
# create service account to log in with
kubectl apply -f resources/dashboard.yaml
# fetch token for this service account (use this to log in to Dashboard)
kubectl -n kubernetes-dashboard create token admin-user
# proxy Dashboard to localhost so you can get to it from localhost:8081
# note this blocks the current session so either run in a new session or in the background
kubectl proxy
```

After a while you can open the Dashboard by opening this URL (and use token from above):
http://localhost:8001/api/v1/namespaces/kubernetes-dashboard/services/https:kubernetes-dashboard:/proxy/

or:

kubectl proxy --port 8081
<http://localhost:8081/api/v1/namespaces/kubernetes-dashboard/services/https:kubernetes-dashboard:/proxy/>


Install Cert-Manager (<https://cert-manager.io/docs/installation/>)

```sh
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.11.1/cert-manager.yaml
```

# Create "root-ca" secret in Cert-Manager's namespace with our Root CA keys
#
# ```sh
# kubectl create secret tls -n cert-manager root-ca \
#   --cert=certificates/root-ca.pem \
#   --key=certificates/root-ca-key.pem
# ```

Wait a bit (for cert-manager webook callback and all Cert-Manager stuff to finish...) then create our Cert-Manager ClusterIssuer along with a test certificate

```sh
kubectl apply -f resources/cert-manager.yaml
```

Check output of certificate:

```sh
kubectl get secrets -n cert-manager
kubectl get secrets -n cert-manager selfsigned-cert-test-tls -o yaml
```

Install Kong (kind variant, see <https://kind.sigs.k8s.io/docs/user/ingress/#ingress-kong>, modified for WSL2, see <https://kind.sigs.k8s.io/docs/user/using-wsl2/>)

```sh
kubectl apply -f https://raw.githubusercontent.com/Kong/kubernetes-ingress-controller/master/deploy/single/all-in-one-dbless.yaml
# Update to use our forwarded ports 30080 and 30443
kubectl patch deployment -n kong proxy-kong -p '{"spec":{"template":{"spec":{"containers":[{"name":"proxy","ports":[{"containerPort":8000,"hostPort":30080,"name":"proxy","protocol":"TCP"},{"containerPort":8443,"hostPort":30443,"name":"proxy-ssl","protocol":"TCP"}]}],"nodeSelector":{"ingress-ready":"true"},"tolerations":[{"key":"node-role.kubernetes.io/control-plane","operator":"Equal","effect":"NoSchedule"},{"key":"node-role.kubernetes.io/master","operator":"Equal","effect":"NoSchedule"}]}}}}'
# Add X-Forwarded-For headers
kubectl patch deployment -n kong proxy-kong -p '{"spec":{"template":{"spec":{"containers":[{"name":"proxy","env":[{"name":"REAL_IP_HEADER","value":"X-Forwarded-For"},{"name":"KONG_TRUSTED_IPS","value":"0.0.0.0/0,::/0"},{"name":"KONG_REAL_IP_RECURSIVE","value":"on"}]}]}}}}'
# Convert Service to NodePort
kubectl patch service -n kong kong-proxy -p '{"spec":{"type":"NodePort"}}'
```

Try a sample ingress:

```sh
# kubectl apply -f https://kind.sigs.k8s.io/examples/ingress/usage.yaml
# kubectl patch ingress example-ingress -p '{"spec":{"ingressClassName":"kong"}}'
# instead run a patched example including https ingress:
kubectl apply -f resources/example-ingress.yaml

# should output current timestamp
curl http://localhost:30080/foo/whatever/sub/path
curl http://localhost:30080/bar
```

Install Keycloak Operator for "Vanilla" Kubernetes (<https://www.keycloak.org/operator/installation#_vanilla_kubernetes_installation>)


```sh
kubectl apply -f https://raw.githubusercontent.com/keycloak/keycloak-k8s-resources/21.1.1/kubernetes/keycloaks.k8s.keycloak.org-v1.yml
kubectl apply -f https://raw.githubusercontent.com/keycloak/keycloak-k8s-resources/21.1.1/kubernetes/keycloakrealmimports.k8s.keycloak.org-v1.yml
```

Create Keycloak Namespace and dependencies:

```sh
kubectl apply -f resources/keycloak-init.yaml
# Install Keycloak Operator in keycloak namespace
kubectl -n keycloak apply -f https://raw.githubusercontent.com/keycloak/keycloak-k8s-resources/21.1.1/kubernetes/kubernetes.yml
# create our Keycloak instance
kubectl apply -f resources/keycloak.yaml
```


# Install OPA Gatekeeper:
# 
# ```sh
# kubectl apply -f https://raw.githubusercontent.com/open-policy-agent/gatekeeper/master/deploy/gatekeeper.yaml
# ```


Install Couchbase (via Helm)

```sh
#helm repo add couchbase https://couchbase-partners.github.io/helm-charts/
#helm repo update
#wget -O resources/couchbase-default-values.yaml https://raw.githubusercontent.com/couchbase-partners/helm-charts/master/charts/couchbase-operator/values.yaml
#helm install kindcouchbase --set cluster.name=cb couchbase/couchbase-operator
#helm status kindcouchbase

# https://docs.couchbase.com/operator/current/install-kubernetes.html
wget https://packages.couchbase.com/couchbase-operator/2.4.1/couchbase-autonomous-operator_2.4.1-kubernetes-linux-amd64.tar.gz
tar xzvf couchbase-autonomous-operator_2.4.1-kubernetes-linux-amd64.tar.gz
cd couchbase-autonomous-operator_2.4.1-130-kubernetes-linux-amd64
kubectl create -f crd.yaml
kubectl create namespace couchbase-operator
bin/cao create admission --namespace couchbase-operator --scope cluster
bin/cao create operator --namespace couchbase-operator --scope cluster
cd ..
```






Use NGINX instead??




Install NGINX

```sh
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
```

Test an ingress

```sh
# First wait until the nginx controller pod is ready
kubectl wait --namespace ingress-nginx \
  --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=90s

# create an example ingress
kubectl apply -f https://kind.sigs.k8s.io/examples/ingress/usage.yaml

# test its output!

# should output "foo-app"
curl localhost/foo/hostname
# should output "bar-app"
curl localhost/bar/hostname
```













kind delete cluster --name kubernetes-lab
kind create cluster --name kubernetes-lab --config kind-config.yaml


# Add kubernetes-dashboard repository
helm repo add kubernetes-dashboard https://kubernetes.github.io/dashboard/
# Deploy a Helm Release named "kubernetes-dashboard" using the kubernetes-dashboard chart
helm install kubernetes-dashboard kubernetes-dashboard/kubernetes-dashboard

helm repo add jetstack https://charts.jetstack.io
helm repo update

helm dependency build cert-manager/
K8S_NAMESPACE="cert-manager" helm install cert-manager-lab cert-manager --namespace $K8S_NAMESPACE --create-namespace --set certmanager.namespace=$K8S_NAMESPACE
