
import json, os
from kubernetes import client, config

def onTrigger(context, session):
    flowfile = session.get()
    if not flowfile:
        return

    try:
        config.load_incluster_config()
        v1 = client.CoreV1Api()
        # List pods in the default namespace
        pods = v1.list_namespaced_pod(namespace="default", limit=3)
        
        flowfile.addAttribute("k8s_pods_found", str(len(pods.items)))
        flowfile.addAttribute("status", "ok")
        session.transfer(flowfile, REL_SUCCESS)
    except Exception as e:
        flowfile.addAttribute("error", str(e))
        session.transfer(flowfile, REL_FAILURE)
