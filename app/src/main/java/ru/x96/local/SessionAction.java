// SPDX-License-Identifier: GPL-3.0-only
package ru.x96.local;

/** Confirmation belongs to one connection object and epoch; never transferable. */
final class SessionAction {
    final Object target;final int generation;private boolean used;
    SessionAction(Object t,int g){if(t==null)throw new IllegalArgumentException("No session");target=t;generation=g;}
    boolean matches(Object current,int epoch){return !used&&target==current&&generation==epoch;}
    boolean take(Object current,int epoch,boolean busy,boolean destroyed){
        if(busy||destroyed||!matches(current,epoch))return false;used=true;return true;
    }
}
