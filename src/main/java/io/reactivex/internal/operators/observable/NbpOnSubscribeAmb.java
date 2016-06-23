/**
 * Copyright 2016 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */

package io.reactivex.internal.operators.observable;

import java.util.concurrent.atomic.*;

import io.reactivex.*;
import io.reactivex.disposables.Disposable;
import io.reactivex.internal.disposables.*;
import io.reactivex.plugins.RxJavaPlugins;

public final class NbpOnSubscribeAmb<T> implements ObservableConsumable<T> {
    final ObservableConsumable<? extends T>[] sources;
    final Iterable<? extends ObservableConsumable<? extends T>> sourcesIterable;
    
    public NbpOnSubscribeAmb(ObservableConsumable<? extends T>[] sources, Iterable<? extends ObservableConsumable<? extends T>> sourcesIterable) {
        this.sources = sources;
        this.sourcesIterable = sourcesIterable;
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public void subscribe(Observer<? super T> s) {
        ObservableConsumable<? extends T>[] sources = this.sources;
        int count = 0;
        if (sources == null) {
            sources = new Observable[8];
            for (ObservableConsumable<? extends T> p : sourcesIterable) {
                if (count == sources.length) {
                    Observable<? extends T>[] b = new Observable[count + (count >> 2)];
                    System.arraycopy(sources, 0, b, 0, count);
                    sources = b;
                }
                sources[count++] = p;
            }
        } else {
            count = sources.length;
        }
        
        if (count == 0) {
            EmptyDisposable.complete(s);
            return;
        } else
        if (count == 1) {
            sources[0].subscribe(s);
            return;
        }

        AmbCoordinator<T> ac = new AmbCoordinator<T>(s, count);
        ac.subscribe(sources);
    }
    
    static final class AmbCoordinator<T> implements Disposable {
        final Observer<? super T> actual;
        final AmbInnerSubscriber<T>[] subscribers;
        
        final AtomicInteger winner = new AtomicInteger();
        
        @SuppressWarnings("unchecked")
        public AmbCoordinator(Observer<? super T> actual, int count) {
            this.actual = actual;
            this.subscribers = new AmbInnerSubscriber[count];
        }
        
        public void subscribe(ObservableConsumable<? extends T>[] sources) {
            AmbInnerSubscriber<T>[] as = subscribers;
            int len = as.length;
            for (int i = 0; i < len; i++) {
                as[i] = new AmbInnerSubscriber<T>(this, i + 1, actual);
            }
            winner.lazySet(0); // release the contents of 'as'
            actual.onSubscribe(this);
            
            for (int i = 0; i < len; i++) {
                if (winner.get() != 0) {
                    return;
                }
                
                sources[i].subscribe(as[i]);
            }
        }
        
        public boolean win(int index) {
            int w = winner.get();
            if (w == 0) {
                if (winner.compareAndSet(0, index)) {
                    AmbInnerSubscriber<T>[] a = subscribers;
                    int n = a.length;
                    for (int i = 0; i < n; i++) {
                        if (i + 1 != index) {
                            a[i].dispose();
                        }
                    }
                    return true;
                }
                return false;
            }
            return w == index;
        }
        
        @Override
        public void dispose() {
            if (winner.get() != -1) {
                winner.lazySet(-1);
                
                for (AmbInnerSubscriber<T> a : subscribers) {
                    a.dispose();
                }
            }
        }
    }
    
    static final class AmbInnerSubscriber<T> extends AtomicReference<Disposable> implements Observer<T>, Disposable {
        /** */
        private static final long serialVersionUID = -1185974347409665484L;
        final AmbCoordinator<T> parent;
        final int index;
        final Observer<? super T> actual;
        
        boolean won;
        
        public AmbInnerSubscriber(AmbCoordinator<T> parent, int index, Observer<? super T> actual) {
            this.parent = parent;
            this.index = index;
            this.actual = actual;
        }
        
        @Override
        public void onSubscribe(Disposable s) {
            DisposableHelper.setOnce(this, s);
        }
        
        @Override
        public void onNext(T t) {
            if (won) {
                actual.onNext(t);
            } else {
                if (parent.win(index)) {
                    won = true;
                    actual.onNext(t);
                } else {
                    get().dispose();
                }
            }
        }
        
        @Override
        public void onError(Throwable t) {
            if (won) {
                actual.onError(t);
            } else {
                if (parent.win(index)) {
                    won = true;
                    actual.onError(t);
                } else {
                    get().dispose();
                    RxJavaPlugins.onError(t);
                }
            }
        }
        
        @Override
        public void onComplete() {
            if (won) {
                actual.onComplete();
            } else {
                if (parent.win(index)) {
                    won = true;
                    actual.onComplete();
                } else {
                    get().dispose();
                }
            }
        }
        
        @Override
        public void dispose() {
            DisposableHelper.dispose(this);
        }
        
    }
}