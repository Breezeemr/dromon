import { Namespace, Context } from "@ory/keto-namespace-types"

class User implements Namespace { }

class Group implements Namespace {
    related: {
        members: (User | Group)[]
    }
}

class fhir implements Namespace { }
