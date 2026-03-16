import type {ReactNode} from 'react';
import clsx from 'clsx';
import Link from '@docusaurus/Link';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';

import styles from './index.module.css';

function HomepageHeader() {
  const {siteConfig} = useDocusaurusContext();
  return (
    <header className={clsx('hero hero--primary', styles.heroBanner)}>
      <div className="container">
        <Heading as="h1" className="hero__title">
          {siteConfig.title}
        </Heading>
        <p className="hero__subtitle">{siteConfig.tagline}</p>
        <div className={styles.buttons}>
          <Link
            className="button button--secondary button--lg"
            to="/docs/intro">
            Get Started
          </Link>
          <Link
            className="button button--outline button--secondary button--lg"
            style={{marginLeft: '1rem'}}
            href="https://github.com/keel-kt/keel">
            GitHub
          </Link>
        </div>
      </div>
    </header>
  );
}

function Feature({title, description}: {title: string; description: string}) {
  return (
    <div className={clsx('col col--4')}>
      <div className="padding-horiz--md padding-vert--lg">
        <Heading as="h3">{title}</Heading>
        <p>{description}</p>
      </div>
    </div>
  );
}

const features = [
  {
    title: 'Native Performance',
    description:
      'Drives epoll (Linux) and kqueue (macOS) directly from Kotlin Native. ' +
      'On JVM, delegates to Netty for battle-tested reliability.',
  },
  {
    title: 'Kotlin Multiplatform',
    description:
      'Single codebase for Linux, macOS, JVM, and Node.js. ' +
      'Engine-independent codec layer works on every target via kotlinx.io.',
  },
  {
    title: 'Ktor Compatible',
    description:
      "Designed as a Ktor Native engine. Bring Ktor's DSL to Native binaries " +
      'without the JVM.',
  },
];

export default function Home(): ReactNode {
  const {siteConfig} = useDocusaurusContext();
  return (
    <Layout
      title={siteConfig.title}
      description="KMP Native Network I/O Engine — epoll, kqueue, NIO, Netty, Node.js">
      <HomepageHeader />
      <main>
        <section style={{padding: '2rem 0'}}>
          <div className="container">
            <div className="row">
              {features.map((f) => (
                <Feature key={f.title} {...f} />
              ))}
            </div>
          </div>
        </section>
      </main>
    </Layout>
  );
}
